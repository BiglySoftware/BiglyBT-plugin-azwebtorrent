/*
 * Created on Jan 6, 2016
 * Created by Paul Gardner
 * 
 * Copyright 2016 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package org.parg.azureus.plugins.webtorrent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import com.biglybt.core.security.SESecurityManager;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.ByteArrayHashMap;
import com.biglybt.core.util.ByteEncodedKeyHashMap;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SystemTime;
import com.biglybt.pifimpl.local.clientid.ClientIDManagerImpl;
import org.parg.azureus.plugins.webtorrent.WebRTCProvider.Answer;
import org.parg.azureus.plugins.webtorrent.WebRTCProvider.Offer;

import com.biglybt.util.JSONUtils;

public class 
TrackerProxy 
	implements LocalWebServer.Listener
{
	private static final boolean TRACE = false;
	
	private final WebTorrentPlugin		plugin;
	private final Listener				listener;
	
	private Map<String,ClientSession>	client_sessions = new HashMap<String, ClientSession>();

	private ByteArrayHashMap<byte[]>	hash_to_peer_id_map 		= new ByteArrayHashMap<>();
	private ByteArrayHashMap<byte[]>	hash_to_scrape_peer_id_map 	= new ByteArrayHashMap<>();
	
	protected
	TrackerProxy(
		WebTorrentPlugin	_plugin,
		Listener			_listener )
	{
		plugin		= _plugin;
		listener	= _listener;
	}
	
	@Override
	public void 
	handleRequest(
		String 			original_url, 
		OutputStream 	os )
			
		throws Exception 
	{
		try{
			int	q_pos = original_url.indexOf( '?' );
			
			final String	ws_url = original_url.substring( 0,  q_pos );
			
			String[] bits = original_url.substring( q_pos+1 ).split( "&" );
			
			int		numwant		= 0;
			long	uploaded	= 0;
			long	downloaded	= 0;
			long	left		= 0;
			
			String	event		= null;
			byte[]	info_hash	= null;
			byte[]	peer_id		= null;
			
			for ( String bit: bits ){
				
				String[]	temp = bit.split( "=" );
				
				String	lhs = temp[0];
				String	rhs	= temp[1];
				
				if ( lhs.equals( "numwant" )){
					
					numwant = Integer.parseInt( rhs );
					
				}else if ( lhs.equals( "uploaded" )){
					
					uploaded = Long.parseLong( rhs );
					
				}else if ( lhs.equals( "downloaded" )){
					
					downloaded = Long.parseLong( rhs );
					
				}else if ( lhs.equals( "left" )){
					
					left = Long.parseLong( rhs );
					
				}else if ( lhs.equals( "event" )){
					
					event	= rhs;
					
				}else if ( lhs.equals( "info_hash" )){
	
					info_hash = URLDecoder.decode( rhs, "ISO-8859-1" ).getBytes( "ISO-8859-1" );
					
				}else if ( lhs.equals( "peer_id" )){
	
					peer_id = URLDecoder.decode( rhs, "ISO-8859-1" ).getBytes( "ISO-8859-1" );
				}		
			}
			
			if ( info_hash == null ){
				
				throw( new Exception( "hash missing" ));
			}
			
		  	String	read_timeout_str 	= System.getProperty("sun.net.client.defaultReadTimeout"); 

		  	int	read_timeout	= Integer.parseInt( read_timeout_str );
		  	
		  	read_timeout = Math.max( read_timeout, 30*1000 );
		  	
			boolean	is_stop = event != null && event.equals( "stopped" );
			
			final boolean scrape = peer_id == null;
			
			final List<Offer>	offers;
			
			if ( scrape ){
				
				offers = null;
				
				numwant	= 0;
				
				synchronized( hash_to_scrape_peer_id_map ){
					
					peer_id = hash_to_scrape_peer_id_map.get( info_hash );
					
					if ( peer_id == null ){
				
						peer_id = ClientIDManagerImpl.getSingleton().generatePeerID( info_hash, true );
						
						hash_to_scrape_peer_id_map.put( info_hash, peer_id );
					}
				}
			}else{
				
				synchronized( hash_to_peer_id_map ){
				
					hash_to_peer_id_map.put( info_hash, peer_id );
				}
				
				if ( is_stop ){
				
					offers = null;
					
				}else{
					
					long	start = SystemTime.getMonotonousTime();
					
					int	offers_to_generate = left==0?Math.min( numwant, 4 ):Math.min( numwant,  8 );
						
					final AESemaphore	sem = new AESemaphore( "" );
					
					offers = new ArrayList<>();
					
					for ( int i=0;i<offers_to_generate;i++){

						listener.getOffer( 
							info_hash, read_timeout - 5*1000,
							new WebRTCProvider.OfferListener() {
								
								boolean done = false;
								
								@Override
								public void gotOffer(Offer offer) {
									synchronized( sem ){
										if ( done ){
											return;
										}
										done = true;
										offers.add( offer );
									}
									sem.release();
								}
								
								@Override
								public void failed() {
									synchronized( sem ){
										if ( done ){
											return;
										}
										done = true;
									}
									sem.release();
								}
							});
					}
					
					for ( int i=0;i<offers_to_generate;i++){
						
						sem.reserve();
					}
					
					if ( offers_to_generate > 0 && offers.size() == 0 ){
						
						throw( new IOException( "WebTorrent proxy appears to be unavailable" ));
						
					}else{
						
						long	elapsed = SystemTime.getMonotonousTime() - start;
						
						//System.out.println( "Offer generation took " + elapsed );
					}
				}
			}
			
			if ( offers != null || scrape || is_stop ){
								
					// roll it by hand, something messes up if we try and use JSONUtils :(
				
				String offer_str = "";
				
				if ( offers != null ){
					
					for ( Offer offer: offers ){
						
						offer_str += 
							(offer_str.length()==0?"":",") + 
							"{\"offer\":{\"type\":\"offer\",\"sdp\":\"" + WebTorrentPlugin.encodeForJSON( offer.getSDP()) + "\"},\"offer_id\":\"" + WebTorrentPlugin.encodeForJSON( offer.getOfferID()) + "\"}";
					}
				}
								
					// on 2016/04/08 I discovered that the tracker protocol had been rewritten to pass "announce" and "scrape" as the action rather than just 
					// the number 1 (and we added 2 for scrape). 
					// Server code also updated to handle multiple info-hashes (and a single one too so we're good for the moment mebe)
				
	        	final String announce = 
	        			"{\"numwant\":" + numwant + 
	        			",\"uploaded\":" + uploaded + 
	        			",\"downloaded\":" + downloaded + 
	        			",\"left\":" + left + 
	        			",\"action\":" + (scrape?"\"scrape\"":"\"announce\"") +
	        			(event==null?"": ( ",\"event\":\"" + event + "\"" )) + 
	        			",\"info_hash\":\"" + WebTorrentPlugin.encodeForJSON( info_hash ) + "\"" +
	        			",\"peer_id\":\"" + WebTorrentPlugin.encodeForJSON( peer_id ) + "\"" +
	        			(scrape?"":( ",\"offers\":[" + offer_str + "]" )) +
	        			"}";
	        		        			
	        	trace( "sending: " + announce );
	        	
	        	ClientSession	client_session = null;
				
				synchronized( client_sessions ){
					
					client_session = client_sessions.get( ws_url );
					
					if ( client_session != null && !client_session.isOpen()){
						
						client_sessions.remove( ws_url );
						
						client_session = null;
					}
				}
			
				if ( client_session != null ){
					
					trace( "announce: old connection to " + ws_url + ": " + announce);
					
					client_session.send( announce );
					
				}else{
					
		            ClientManager client = ClientManager.createClient();
		            
		            if ( ws_url.toLowerCase( Locale.US ).startsWith( "wss")){
		            	
			            TrustManager[] trustAllCerts = SESecurityManager.getAllTrustingTrustManager();				
						
						SSLContext sc = SSLContext.getInstance("SSL");
							
						sc.init( null, trustAllCerts, RandomUtils.SECURE_RANDOM );
			         
				        SSLEngineConfigurator sslEngineConfigurator = new SSLEngineConfigurator(sc );
				        
			            client.getProperties().put( ClientProperties.SSL_ENGINE_CONFIGURATOR, sslEngineConfigurator);
		            }
		            
		            ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
		            
		            Session session = 
		            	client.connectToServer(
		            		new Endpoint() 
		            		{
				                @Override
				                public void 
				                onOpen(
				                	final Session 		session, 
				                	EndpointConfig 		config) 
				                {
				                    try{
				                    	
				                        session.addMessageHandler(new MessageHandler.Whole<String>() {
	
				                            @Override
				                            public void 
				                            onMessage(
				                            	String message ) 
				                            {
				                            	trace("Received message from " + ws_url + ", offers=" + (offers==null?null:offers.size()) + ": " + message);
	
				                            	Map map = JSONUtils.decodeJSON( message );
				                            			                            	
				                            	if ( map.containsKey( "answer" )){
				                            	
				                            		try{
				                            			Map answer = (Map)map.get( "answer" );
				                            		
				                            			String offer_id = (String)map.get( "offer_id" );
				                            		
				                            			String hash_str = (String)map.get( "info_hash" );
					                            		
					                            		byte[] hash = hash_str.getBytes( "ISO-8859-1" );
					                            		
				                            			String sdp = (String)answer.get( "sdp" );
				                            		
				                            			listener.gotAnswer( hash, offer_id, sdp );
				                            			
				                            		}catch( Throwable e ){
				                            			
				                            			Debug.out( e );
				                            		}
				                            	}else if ( map.containsKey( "offer" )){
				                            		
				                            		try{
					                            		Map offer = (Map)map.get( "offer" );
					                            		
					                            		String offer_id = (String)map.get( "offer_id" );
					                            		
					                            		final String to_peer_id = (String)map.get( "peer_id" );
					                            		
					                            		final String info_hash = (String)map.get( "info_hash" );
					                            		
					                            		final byte[] hash = info_hash.getBytes( "ISO-8859-1" );
				                            							                            		
				                            			String sdp = (String)offer.get( "sdp" );
				                            		
				                            			final byte[]	peer_id;
				                            		
				                            			synchronized( hash_to_peer_id_map ){
				                            				
				                            				peer_id = hash_to_peer_id_map.get( hash );
				                            			}
				                            			
				                            			if ( peer_id != null ){
				                            				
					                            			listener.gotOffer( 
					                            				hash, 
					                            				offer_id, 
					                            				sdp,
					                            				new WebRTCProvider.AnswerListener() {
																	
																	@Override
																	public void 
																	gotAnswer(
																		Answer answer ) 
																	{
																		String answer_str = "{\"action\":\"announce\",\"answer\":{\"type\":\"answer\",\"sdp\":\"" + 
																				WebTorrentPlugin.encodeForJSON( answer.getSDP()) + "\"}," + 
																				"\"offer_id\":\"" + WebTorrentPlugin.encodeForJSON( answer.getOfferID()) + "\"," + 
																				"\"peer_id\":\"" + WebTorrentPlugin.encodeForJSON( peer_id ) + "\"," + 
																				"\"to_peer_id\":\"" + WebTorrentPlugin.encodeForJSON( to_peer_id ) + "\"," + 
																				"\"info_hash\":\"" + WebTorrentPlugin.encodeForJSON( hash ) + "\"" + 
																				"}";
	
																		trace( answer_str );
																		
																		try{
																			session.getBasicRemote().sendText( answer_str );
																			
																			trace( "    answer sent" );
																			
																		 }catch( Throwable e ){
										                            			
											                            	//	Debug.out( e );
																			 
																			 trace( "Failed to send answer: " + Debug.getNestedExceptionMessage( e ));
																		 }
																	}
																	
																	@Override
																	public void failed()
																	{
																	}
																});
				                            			}else{
				                            				
				                            				trace( "Peer id for " + ByteFormatter.encodeString( hash ) + " not found" );
				                            			}
				                            		}catch( Throwable e ){
				                            			
				                            			Debug.out( e );
				                            		}
				                            		
				                            	}else if ( map.containsKey( "complete" )){
				                            		
				                            		synchronized( client_sessions ){
							                    		 
							                    		 ClientSession s = client_sessions.get( ws_url );
								                		 
								                		 if ( s.getSession() == session ){
								                			 
								                			 try{
							                            		String hash_str = (String)map.get( "info_hash" );

								                            	byte[] hash = hash_str.getBytes( "ISO-8859-1" );
								                			 
								                            	s.announceReceived( hash, map );
								                            	
								                			 }catch( Throwable e ){
							                            			
							                            		Debug.out( e );
							                            	}
								                		 }
								                	 }
				                            	}else if ( map.containsKey( "files" )){
				                            		
				                            			// scrape response
				                            		
				                            		Map<String,Map> files = (Map<String,Map>)map.get( "files" );
				                            		
				                            		synchronized( client_sessions ){
							                    		 
							                    		 ClientSession s = client_sessions.get( ws_url );
								                		 
								                		 if ( s.getSession() == session ){

								                			 for ( String info_hash: files.keySet()){

								                				 try{
								                					 
								                					 byte[] hash = info_hash.getBytes( "ISO-8859-1" );
								                					 
								                					 s.announceReceived( hash, map );
								                					 
								                				 }catch( Throwable e ){

								                					 Debug.out( e );
								                				 }
								                			 }
								                		 }
				                            		}
				                            	}
				                            }
				                        });
				                      		
				                        trace( "announce: new connection to " + ws_url + announce );
				                        
				                        session.getBasicRemote().sendText( announce );
				                        
				                    }catch( IOException e ){
				                    	
				                    	 synchronized( client_sessions ){
				                    		 
				                    		 ClientSession s = client_sessions.get( ws_url );
					                		 
					                		 if ( s.getSession() == session ){
					                			 
					                			 s.close();
					                			 
					                			 client_sessions.remove( ws_url );
					                		 }
				                    	 }
				                    }
				                }
				                
				                @Override
				                public void 
				                onError(
				                	Session 	session, 
				                	Throwable 	e )
				                {
				                	 synchronized( client_sessions ){
				                		 
				                		 ClientSession s = client_sessions.get( ws_url );
				                		 
				                		 if ( s.getSession() == session ){
				                			 
				                			 s.close();
				                			 
				                			 client_sessions.remove( ws_url );
				                		 }
				                	 }
				                }
				            }, cec, new URI( ws_url ));
	            
		            client_session = new ClientSession( session );
		            
		            synchronized( client_sessions ){
		            	
		            	ClientSession existing = client_sessions.put( ws_url, client_session );
		            	
		            	if ( existing != null ){
		            		
		            		try{
		            			existing.close();
		            			
		            		}catch( Throwable e ){
		            			
		            		}
		            	}
		            }
				}   
				
				Map reply = client_session.waitForAnnounce( info_hash, read_timeout - 5*1000 );
				
				if ( reply == null ){
					
					throw( new Exception( "Timeout" ));
				}
				
				byte[] reply_bytes;
				
				if ( scrape ){
					
					if ( reply.containsKey( "files" )){
						
						Map<String,Object>	root = new HashMap<>();
						
						root.putAll( reply );
						
						ByteEncodedKeyHashMap<String, Object>	files = new ByteEncodedKeyHashMap<>();
						
						files.putAll((Map)reply.get( "files" ));
						
						root.put( "files", files);
												
						reply_bytes = BEncoder.encode( root );
						
					}else{
							// old style before tracker protocol supported scrape
						
						Map<String,Object>	root = new HashMap<>();
						
						ByteEncodedKeyHashMap<String, Object>	files = new ByteEncodedKeyHashMap<>();
						
						root.put( "files", files );
						
						files.put( new String( info_hash,Constants.BYTE_ENCODING_CHARSET ), reply );
						
						reply_bytes = BEncoder.encode( root );
					}
					
				}else{
					
					reply_bytes = BEncoder.encode( reply );
				}
				
				
				String[] reply_lines = {
						
						"HTTP/1.1 200 OK",
						"Content-Length: " + reply_bytes.length,
						"Connection: close"
				};
				
				for ( String str: reply_lines ){
					
					os.write((str + "\r\n" ).getBytes( "ISO-8859-1" ));
				}
				
				os.write( "\r\n" .getBytes( "ISO-8859-1" ));
				
				os.write( reply_bytes );
				
			}else{
				
				throw( new Exception( "WebSocket proxy offer not available" ));
			}
		}catch( Throwable e ){
			
			Map	failure = new HashMap();
			
			failure.put( "failure reason", Debug.getNestedExceptionMessage( e ));
			
			try{
				byte[] x = BEncoder.encode( failure );
							
				String[] reply_lines = {
				
						"HTTP/1.1 200 OK",
						"Content-Length: " + x.length,
						"Connection: close"
				};
				
				for ( String str: reply_lines ){
					
					os.write((str + "\r\n" ).getBytes( "ISO-8859-1" ));
				}
				
				os.write( "\r\n" .getBytes( "ISO-8859-1" ));
				
				os.write( x );
				
			}catch( Throwable f ){
				
				//Debug.printStackTrace(f);
			}
		}
	}
	
	public void
	destroy()
	{
		synchronized( client_sessions ){
		 
			 for ( ClientSession s: client_sessions.values()){
			 
				 try{
					 s.close();
				 
				 }catch( Throwable e ){
				 }
			 }
			 
			 client_sessions.clear();
		 }
	}
    
    public interface
    Listener
    {
    	public void
    	getOffer(
    		byte[]							hash,
    		long							timeout,
    		WebRTCProvider.OfferListener	offer_listener );
    	
    	public void
    	gotAnswer(
    		byte[]		hash,
    		String		offer_id,
    		String		sdp )
    		
    		throws Exception;
    	
    	public void
    	gotOffer(
    		byte[]							hash,
    		String							offer_id,
    		String							sdp,
    		WebRTCProvider.AnswerListener	listener )
    		
    		throws Exception;
    }
    
    private class
    ClientSession
    {
    	private Session		session;
    	
    	private ByteArrayHashMap<AnnounceData>	announce_map = new ByteArrayHashMap<>();
    	    	
    	protected
    	ClientSession(
    		Session		_session )
    	{
    		session	= _session;
    	}
    	
    	private void
    	send(
    		String		str )
    		
    		throws Exception
    	{
    		session.getBasicRemote().sendText( str );
    	}
    	
    	private Session
    	getSession()
    	{
    		return( session );
    	}
    	
    	private boolean
    	isOpen()
    	{
    		return( session.isOpen());
    	}
    	
    	private void
    	announceReceived(
    		byte[]		hash,
    		Map			map )
    	{
    		synchronized( this ){
    			
    			AnnounceData	ad = announce_map.get( hash );
    			
    			if ( ad == null ){
    				
    				ad = new AnnounceData();
    				
    				announce_map.put( hash, ad );
    			}
    			
    			ad.last_announce 		= map;
    			ad.last_announce_time	= SystemTime.getMonotonousTime();
    			
    			if ( ad.announce_sem != null ){
    				
    				ad.announce_sem.releaseForever();
    				
    				ad.announce_sem = null;
    			}
    		}
    	}
    	
    	private Map
    	waitForAnnounce(
    		byte[]		hash,
    		long		timeout )
    	{
    		AnnounceData	ad;
    		AESemaphore 	sem;
    		
    		synchronized( this ){
    			
    			ad = announce_map.get( hash );
    			
    			if ( ad == null ){
    				
    				ad = new AnnounceData();
    				
    				announce_map.put( hash, ad );
    			}
    			
    			if ( ad.last_announce != null && SystemTime.getMonotonousTime() - ad.last_announce_time < 10000 ){
    				
    				return( ad.last_announce );
    			}
    			
    			if ( ad.announce_sem == null ){
    				
    				ad.announce_sem = new AESemaphore( "" );
    			}
    			
    			sem = ad.announce_sem;
    		}
    		
    		sem.reserve( timeout );
    		
    		return( ad.last_announce );
    	}
    	
    	private void
    	close()
    	{
    		try{
    			session.close();
    			
    		}catch( Throwable e ){
    			
    		}
    	}
    }
    
    private class
    AnnounceData
    {
    	private Map			last_announce;
    	private long		last_announce_time;
    	private AESemaphore	announce_sem;

    }
    
    private void
    trace(
    	String		str )
    {
    	if ( TRACE ){
    		System.out.println( str );
    	}
    }
}
