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


package org.parg.azureus.plugins.webtorrent.webrtc.browser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;







import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.Base32;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import org.parg.azureus.plugins.webtorrent.WebRTCProvider;
import org.parg.azureus.plugins.webtorrent.WebTorrentPlugin;
import org.parg.azureus.plugins.webtorrent.webrtc.WebRTCPeerBridge;


public class 
JavaScriptProxyImpl 
	implements WebRTCProvider
{
	private static Object	lock 			= new Object();
	private static long		next_offer_id	= RandomUtils.nextAbsoluteLong();
	
	private static boolean				server_started = false;
	
	private static volatile JavaScriptProxyImpl	current_proxy;
		
	private final WebRTCPeerBridge	peer_bridge;
	
	private final long	instance_id;	
	
	private JavaScriptProxyInstance.ServerWrapper	current_server;
	
	private JavaScriptProxyInstance		current_instance;
	private AESemaphore					current_instance_sem = new AESemaphore("");
	
	private Map<String,OfferAnswerImpl>		offer_answer_map = new HashMap<>();
	
	private boolean		destroyed;
	
	public
	JavaScriptProxyImpl(
		WebTorrentPlugin		_plugin,
		WebRTCPeerBridge		_peer_bridge,
		long					_instance_id,
		final Callback			_callback )
		
		throws Exception
	{		
		peer_bridge = _peer_bridge;
		
		synchronized( lock ){
						
			instance_id		= _instance_id;
			
			current_proxy	= this;
		
			if ( !server_started ){
								
				current_server = 
					JavaScriptProxyInstance.startServer(
						new JavaScriptProxyInstance.Listener()
						{
							@Override
							public void 
							controlCreated(
								JavaScriptProxyInstance inst ) 
							{
								peer_bridge.reset();
								
								current_proxy.instanceCreated( inst );
							}
							
							@Override
							public void 
							controlDestroyed(
								JavaScriptProxyInstance inst) 
							{
								current_proxy.instanceDestroyed( inst );
							}
							
							@Override
							public Map<String,Object>
					    	receiveControlMessage(
					    		JavaScriptProxyInstance		inst,
					    		Map<String,Object>			message )
					    	{
								return( current_proxy.receiveMessage( inst, message ));
							}
							
							@Override
					       	public void
					    	peerCreated(
					    		JavaScriptProxyInstance		inst )
					    	{
								peer_bridge.addPeer( inst );
					    	}
					    	
							@Override
					    	public void
					    	receivePeerMessage(
					    		JavaScriptProxyInstance		inst,
					    		ByteBuffer					message )
					    	{
								peer_bridge.receive( inst, message );
					    	}
					    	
							@Override
					    	public void
					    	peerDestroyed(
					    		JavaScriptProxyInstance		inst )
					    	{
								peer_bridge.removePeer( inst );
					    	}
						});
				
				server_started = true;

				SimpleTimer.addPeriodicEvent(
						"WSTimer",
						5*1000,
						new TimerEventPerformer()
						{	
							private long	first_failure;
							
							private int		consecutive_relaunch_requests;
							
							@Override
							public void perform(TimerEvent event) {
							
								JavaScriptProxyInstance	inst;
								
								synchronized( lock ){
									
									inst = current_instance;
								}
								
								boolean	ok = false;
								
								if ( inst != null ){

									try{
										Map ping = new HashMap();

										ping.put( "type", "ping" );

										ping.put( "info", peer_bridge.getInfo());
										
										inst.sendControlMessage( ping );

										ok = true;
										
									}catch( Throwable e ){

										Debug.out( e );
									}
								}
								
								long	now = SystemTime.getMonotonousTime();
								
								if ( ok ){
																		
									first_failure					= 0;
									consecutive_relaunch_requests	= 0;
									
								}else{
									
									if ( first_failure == 0 ){
										
										first_failure = now;
										
									}else{
										
										long	failed_ago = now - first_failure;
																				
										long	delay = 60*1000;
																					
										for ( int i=0;i<consecutive_relaunch_requests;i++){
											
											delay *= 2;
											
											if ( delay > 30*60*1000 ){
												
												delay = 30*60*1000;
												
												break;
											}
										}
										
										//System.out.println( "failed_ago=" + failed_ago + ", relaunches=" + consecutive_relaunch_requests );

										if ( failed_ago > delay ){
											
											consecutive_relaunch_requests++;
											
											first_failure = 0;
											
											_callback.requestNewBrowser();
										}
									}
								}
							}
						});
			}
		}
		SimpleTimer.addPeriodicEvent(
			"WSTimer2",
			5*1000,
			new TimerEventPerformer()
			{					
				@Override
				public void 
				perform(
					TimerEvent event ) 
				{
					long now = SystemTime.getMonotonousTime();
					
					List<OfferAnswerImpl>	expired = new ArrayList<>();
					
					synchronized( lock ){

						Iterator<OfferAnswerImpl>	it = offer_answer_map.values().iterator();
							
						while( it.hasNext()){
							
							OfferAnswerImpl oa = it.next();
							
							if ( oa.getExpiry() < now ){
								
								expired.add( oa );
								
								it.remove();
							}
						}
					}
					
					for ( OfferAnswerImpl oa: expired ){
						
						oa.destroy();
					}
				}
			});
	}
	
	public int
	getPort()
	{
		synchronized( lock ){
		
			if ( current_server == null ){
				
				return( 0 );
			}
			
			return( current_server.getPort() );
		}
	}
	
	public void
	instanceCreated(
		JavaScriptProxyInstance		inst )
	{
		synchronized( lock ){
			
			if ( inst.getInstanceID() != instance_id ){
				
				inst.destroy();
				
				return;
			}
					
			current_instance	= inst;
			current_instance_sem.releaseForever();
		}	
	}
	
	public void
	instanceDestroyed(
		JavaScriptProxyInstance		inst )
	{
    	synchronized( lock ){
    		
    		if ( inst.getInstanceID() != instance_id ){
								
				return;
			}

    		if ( inst == current_instance ){
    			
    			current_instance 		= null;
    			current_instance_sem	= new AESemaphore( "" );
    			
    			for ( OfferAnswerImpl oa: offer_answer_map.values()){
    				
    				oa.destroy();
    			}
    			
    			offer_answer_map.clear();
    		}
    	}
	}
	
	public Map<String,Object>
	receiveMessage(
		JavaScriptProxyInstance		inst,
		Map<String,Object>			message )
	{
		Map<String,Object>	result = new HashMap();
		
    	synchronized( lock ){
    		
    		if ( inst.getInstanceID() != instance_id ){
					
    			inst.destroy();
    			
				return( result );
			}
    		
    		String	offer_id = (String)message.get( "offer_id" );
    		
    		OfferAnswerImpl offer = offer_answer_map.get( offer_id );
    		
    		if ( offer != null ){
    			
    			offer.addMessage( message );
    		}
    	}
    	
    	return( result );
	}
	
	@Override
	public void 
	getOffer(
		byte[]			info_hash,
		long			timeout,
		OfferListener	listener )
	{
		if ( current_instance_sem.reserve( timeout )){
			
			JavaScriptProxyInstance	inst;
			
			String	offer_id;
			
			OfferAnswerImpl	offer;
			
			synchronized( lock ){
			
				inst = current_instance;
				
				if ( inst == null ){
					
					listener.failed();
					
					return;
				}
				
				long oid = next_offer_id++;
				
				if ( oid == 0 ){
					
					oid = next_offer_id++;
				}
				
				offer_id = String.valueOf( oid );
				
				offer = new OfferAnswerImpl( inst, offer_id, timeout, listener );
				
				offer_answer_map.put( offer_id, offer );
			}
				
			try{
				Map<String,Object>	message = new HashMap<>();
					
				message.put( "type", "create_offer" );
				message.put( "info_hash", Base32.encode( info_hash ));
				message.put( "offer_id", offer_id );
					
				inst.sendControlMessage( message );
				
				return;
				
			}catch( Throwable e ){
				
				synchronized( lock ){
					
					offer_answer_map.remove( offer_id );
				}
			}
		}
		
		listener.failed();
	}
	
	@Override
	public void 
	gotAnswer(
		String 			offer_id, 
		String 			sdp) 
	{
		JavaScriptProxyInstance	inst;

		synchronized( lock ){

			inst	= current_instance;
		}
		
		Map to_send = new HashMap();
		
		to_send.put( "type", "answer" );
		to_send.put( "offer_id", offer_id );
		to_send.put( "sdp", sdp );
	
		if ( inst != null ){
		
			try{
				inst.sendControlMessage( to_send );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
	}
	
	@Override
	public void 
	gotOffer(
		byte[]							info_hash,
		String 							external_offer_id, 
		String 							sdp,
		WebRTCProvider.AnswerListener	listener )
	{
		if ( current_instance_sem.reserve( 10*1000 )){
			
			JavaScriptProxyInstance	inst;
			
			String	internal_offer_id;
			
			OfferAnswerImpl	offer;
			
			synchronized( lock ){
			
				inst = current_instance;
				
				if ( inst == null ){
					
					return;
				}
				
				long oid = next_offer_id++;
				
				if ( oid == 0 ){
					
					oid = next_offer_id++;
				}
				
				internal_offer_id = String.valueOf( oid );
				
				offer = new OfferAnswerImpl( inst, external_offer_id, 2*60*1000, listener );
				
				offer_answer_map.put( internal_offer_id, offer );
			}
		
			try{
				Map<String,Object> to_send = new HashMap<>();
				
				to_send.put( "type", "offer" );
				to_send.put( "offer_id", internal_offer_id );
				to_send.put( "info_hash", Base32.encode( info_hash ));
				to_send.put( "sdp", sdp );
			
				inst.sendControlMessage( to_send );
						
			}catch( Throwable e ){
				
				Debug.out( e );
				
				synchronized( lock ){
					
					offer_answer_map.remove( internal_offer_id );
				}
			}
		}
	}
	
	public void
	destroy()
	{
		synchronized( lock ){
		
			server_started	= false;
	
			if ( current_instance != null ){
				
				current_instance.destroy();
				
				current_instance = null;
			}
			
			current_instance_sem = new AESemaphore( "" );
			
			if ( current_server != null ){
				
				current_server.destroy();
				
				current_server = null;
			}
		}
	}
	
    public class
    OfferAnswerImpl
    	implements Offer, Answer
    {
    	private final JavaScriptProxyInstance		inst;
    	private final String						external_offer_id;
    	private final long							expiry;
    	
    	private OfferListener					offer_listener;
    	private AnswerListener					answer_listener;
    	
    	private String			sdp 			= null;
    	private List<String>	candidates 		= new ArrayList<>();
    	
    	private AESemaphore		offer_sem = new AESemaphore( "" );
    	
    	private boolean	destroyed;
    	
       	private
    	OfferAnswerImpl(
    		JavaScriptProxyInstance		_inst,
    		String						_external_offer_id,
    		long						_timeout,
    		OfferListener				_listener )
    	{
    		inst					= _inst;
    		external_offer_id		= _external_offer_id;
    		offer_listener			= _listener;
    		
    		expiry = SystemTime.getMonotonousTime() + _timeout;
    	}
       	
    	private
    	OfferAnswerImpl(
    		JavaScriptProxyInstance		_inst,
    		String						_external_offer_id,
    		long						_timeout,
    		AnswerListener				_listener )
    	{
    		inst					= _inst;
    		external_offer_id		= _external_offer_id;
    		answer_listener			= _listener;
    		
    		expiry = SystemTime.getMonotonousTime() + _timeout;
    	}
    	
    	protected boolean
    	waitFor(
    		long		timeout )
    		
    		throws Exception
    	{
    		boolean done = offer_sem.reserve( timeout );
    		
    		if ( !done ){
    		
    			Debug.out( "Timeout waiting for offer" );
    		}
    		
    		if ( destroyed ){
    			
    			throw( new Exception( "Offer destroyed" ));
    		}
    		
    		return( done );
    	}
    	
    	protected long
    	getExpiry()
    	{
    		return( expiry );
    	}
    	
    	public void
    	addMessage(
    		Map		msg )
    	{
    		String	type = (String)msg.get( "type" );
    		
    		if ( type.equals( "sdp" )){
    			
    			sdp	= (String)msg.get( "sdp" );
    			
    		}else if ( type.equals( "ice_candidate" )){
    			
    			String candidate = (String)msg.get( "candidate" );
    			    			
    			if ( candidate.length() > 0 ){
    				
    				candidates.add( candidate );
    				
    			}else{
    				
    				offer_sem.releaseForever();
    				
    				if ( answer_listener != null ){
    					
    					answer_listener.gotAnswer( this );
    					
    				}else if ( offer_listener != null ){
    					
    					offer_listener.gotOffer( this );
    				}
    			}
    		}
    	}
    	
    	@Override
    	public String
    	getSDP()
    	{	
    		String offer = "";
    		
    		String[] bits = sdp.split( "\n" );
    		
    		boolean	 done_candidates = false;
    		
    		for ( String bit: bits ){
    			
    			bit = bit.trim();
    			
    			if ( bit.length() == 0 ){
    				
    				continue;
    			}
    			
    			if ( bit.startsWith( "a=" ) && !bit.startsWith( "a=msid-" ) && !done_candidates){
    				
    				done_candidates = true;
    				
    				for ( String candidate: candidates ){
    					
    					offer += "a=" + candidate + "\r\n";
    				}
    			}
    			
    			offer += bit + "\r\n";
    		}
    		
    		if ( !done_candidates ){
    			
    			for ( String candidate: candidates ){
					
					offer += "a=" + candidate + "\r\n";
				}
    		}
    		    		    		
    		return( offer );
    	}
    	
    	public String
    	getOfferID()
    	{
    		return( external_offer_id );
    	}
    	
    	private void
    	destroy()
    	{
    		destroyed	= true;
    		
    		offer_sem.releaseForever();
    		
    		if ( answer_listener != null ){
				
				answer_listener.failed();
				
			}else if ( offer_listener != null ){
				
				offer_listener.failed();
			}
    	}
    }	
}
