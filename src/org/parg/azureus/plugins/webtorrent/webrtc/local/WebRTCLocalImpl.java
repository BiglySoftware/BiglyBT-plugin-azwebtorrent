/*
 * Copyright (C) Bigly Software, Inc, All Rights Reserved.
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
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 */

package org.parg.azureus.plugins.webtorrent.webrtc.local;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;

import org.parg.azureus.plugins.webtorrent.WebRTCProvider;
import org.parg.azureus.plugins.webtorrent.WebTorrentPlugin;
import org.parg.azureus.plugins.webtorrent.webrtc.WebRTCPeer;
import org.parg.azureus.plugins.webtorrent.webrtc.WebRTCPeerBridge;


import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCPriorityType;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsCollectorCallback;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;


public class 
WebRTCLocalImpl
	implements WebRTCProvider
{
	private static final boolean 	trace_on = false;
	
	private static Object	lock 			= new Object();
	private static long		next_offer_id	= RandomUtils.nextAbsoluteLong();

	private static AsyncDispatcher	async_dispatcher = new AsyncDispatcher();
	
	private final WebTorrentPlugin		plugin;
	private final WebRTCPeerBridge		peer_bridge;
	
	private PeerConnectionFactory 	factory;
	private RTCConfiguration 		config;
	
	private Map<Long,PeerConnection>	connections = new HashMap<>();
	
	private boolean impl_destroyed;
	
	public
	WebRTCLocalImpl(
		WebTorrentPlugin		_plugin,
		WebRTCPeerBridge		_peer_bridge,
		long					_instance_id,
		final Callback			_callback )
	{
		plugin		= _plugin;
		peer_bridge	= _peer_bridge;
				
		plugin.log( "Local WebRTC provider" );
		
		try{
			factory = new PeerConnectionFactory();
			
			RTCIceServer iceServer = new RTCIceServer();
			
			for ( String url: plugin.getICEUrls()){

				iceServer.urls.add( url );
			}
			
			config = new RTCConfiguration();
			
			config.iceServers.add(iceServer);

			SimpleTimer.addPeriodicEvent(
				"WebRTCLocalImpl:timer",
				5*1000,
				new TimerEventPerformer()
				{					
					@Override
					public void 
					perform(
						TimerEvent event ) 
					{
						List<PeerConnection>		failed = new ArrayList<>();
						
						synchronized( lock ){
							
							trace( "Peer count=" + connections.size());
							
							for ( PeerConnection pc: connections.values()){
								
								if ( pc.hasFailed()){
									
									failed.add( pc );
								}
							}
						}
						
						for ( PeerConnection pc: failed ){
							
							removePeer( pc );
						}
					}
				});
			
		}catch( Throwable e ){
			
			Debug.out(e);
			
			plugin.setStatusError( e );
		}
	}
	
	public int
	getPort()
	{
		return( 0 );
	}
		
	public void
	getOffer(
		byte[]			info_hash,
		long			timeout,
		OfferListener	offer_listener )
	{
		try{
			
			PeerConnection pc = new PeerConnection( info_hash, timeout, offer_listener );
			
			boolean destroy_it = false;
			
			synchronized( lock ){
				
				if ( impl_destroyed ){
					
					destroy_it = true;
					
				}else{

					connections.put( pc.getOfferID(), pc );
				}
			}
			
			if ( destroy_it ){
				
				pc.destroy();
				
				throw( new Exception( "Disposed" ));
			}
		}catch( Throwable e ){
			
			Debug.out( e );
			
			offer_listener.failed();
		}
	}
	
	public void
	gotAnswer(
		String		offer_id,
		String		sdp )
	{
		PeerConnection pc;
		
		synchronized( lock ){
			
			pc = connections.get( Long.parseLong( offer_id ) );
		}
		
		if ( pc == null ){
			
			Debug.out( "No peer connection found for offer " + offer_id );
			
		}else{
			
			pc.gotAnswer( sdp );
		}
	}
	
	public void
	gotOffer(
		byte[]			info_hash,
		String			offer_id,
		String			sdp,
		AnswerListener	answer_listener )
	{
		try{
			
			PeerConnection pc = new PeerConnection( info_hash, offer_id, sdp, answer_listener );
			
			boolean destroy_it = false;
			
			synchronized( lock ){
				
				if ( impl_destroyed ){
					
					destroy_it = true;
					
				}else{

					connections.put( pc.getOfferID(), pc );
				}
			}
			
			if ( destroy_it ){
				
				pc.destroy();
				
				throw( new Exception( "Disposed" ));
			}
		}catch( Throwable e ){
			
			Debug.out( e );
			
			answer_listener.failed();
		}		
	}
	
	void
	removePeer(
		PeerConnection		connection )
	{
		try{
			connection.destroy();
			
		}finally{
			
			synchronized( lock ){
			
				connections.remove( connection.getOfferID());
			}
		}
	}
	
	public void
	destroy()
	{
		List<PeerConnection>	to_destroy;
		
		synchronized( lock ){
			
			if ( impl_destroyed ){
				
				return;
			}
			
			impl_destroyed = true;
			
			to_destroy = new ArrayList<>( connections.values());
			
			connections.clear();
		}

		for ( PeerConnection pc: to_destroy ){
			
			pc.destroy();
		}

		try{
			if ( factory != null ){
				
				factory.dispose();
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	void
	trace(
		String	str )
	{
		if ( trace_on ){
			
			System.out.println( str );
		}
	}
	
	private class
	PeerConnection
	{
		final Object				peer_lock = new Object();
		
		final long					created_time	= SystemTime.getMonotonousTime();

		final byte[]				info_hash;
		final long					offer_id;
		
		final OfferListener			offer_listener;
		final AnswerListener		answer_listener;
		final long					listener_timeout;
		boolean						listener_triggered;
		
		final RTCPeerConnection 	peerConnection;
		
		RTCDataChannel 		channel;
		
		volatile RTCPeerConnectionState 		connection_state;
		
		BridgePeer			bridge_peer;
		
		volatile boolean	pc_destroyed;
		
		PeerConnection(
			byte[]				_info_hash,
			long				_timeout,
			OfferListener		_offer_listener )
		{
			info_hash			= _info_hash;
			
			offer_listener 		= _offer_listener;
			answer_listener		= null;
			
			listener_timeout	= _timeout;
			
			long oid = next_offer_id++;
			
			if ( oid == 0 ){
				
				oid = next_offer_id++;
			}
			
			offer_id = oid;

			peerConnection = 
					factory.createPeerConnection(
						config, 
						new PeerConnectionObserver(){
							
							@Override
							public void 
							onIceCandidate(
								RTCIceCandidate candidate )
							{
								// trace( candidate.sdp );
							}
							
							@Override
							public void 
							onIceCandidateError(
								RTCPeerConnectionIceErrorEvent event)
							{
								// trace( "candidate error: " + event.getAddress() + " - " + event.getErrorText());
							}
							
							@Override
							public void 
							onConnectionChange(
								RTCPeerConnectionState state)
							{
								connection_state = state;
								
								// trace( "state=" + state );
								
								if ( state == RTCPeerConnectionState.FAILED ){
									
									failed( "state->failed");
								}
							}
						});
				
			connection_state = peerConnection.getConnectionState();
			
			RTCDataChannelInit channel_init = new RTCDataChannelInit();
			
			// channel_init.protocol = "arraybuffer";
			
			channel_init.negotiated	= false;
			channel_init.ordered	= true;
			channel_init.priority	= RTCPriorityType.HIGH;
			
			channel = peerConnection.createDataChannel( "biglybt", channel_init );
			
			setupChannel( false );
			
			RTCOfferOptions options = new RTCOfferOptions();
			
			peerConnection.createOffer( options, new CreateSessionDescriptionObserver(){				
				@Override
				public void 
				onSuccess(
					RTCSessionDescription desc)
				{
					peerConnection.setLocalDescription( 
						desc,
						new SetSessionDescriptionObserver(){
							
							@Override
							public void 
							onSuccess()
							{	
								synchronized( peer_lock ){

									if ( listener_triggered ){
										
										return;
									}
									
									listener_triggered = true;
								}
								
								offer_listener.gotOffer(
									new Offer()
									{
										@Override
										public String getOfferID(){
											return( String.valueOf( offer_id ));
										}
										@Override
										public String getSDP(){
											return( desc.sdp );
										}
									});
							}
							
							@Override
							public void 
							onFailure(
								String arg )
							{	
								failed( "setLocalDescription failed: " + arg );
							}
						});
				}
				
				@Override
				public void 
				onFailure(
					String arg )
				{
					failed( "createOffer failed: " + arg );
				}
			});
		}
		
		PeerConnection(
			byte[]				_info_hash,
			String				_remote_offer_id,
			String				_sdp,
			AnswerListener		_accept_listener )
		{
			info_hash			= _info_hash;
			
			offer_listener 		= null;
			answer_listener		= _accept_listener;
			
			listener_timeout	= 0;
			
			long oid = next_offer_id++;
			
			if ( oid == 0 ){
				
				oid = next_offer_id++;
			}
			
			offer_id = oid;

			peerConnection = 
					factory.createPeerConnection(
						config, 
						new PeerConnectionObserver(){
							
							@Override
							public void 
							onIceCandidate(
								RTCIceCandidate candidate )
							{
								// trace( candidate.sdp );
							}
							
							@Override
							public void 
							onIceCandidateError(
								RTCPeerConnectionIceErrorEvent event)
							{
								// trace( "candidate error: " + event.getAddress() + " - " + event.getErrorText());
							}
							
							@Override
							public void 
							onConnectionChange(
								RTCPeerConnectionState state)
							{
								connection_state = state;
								
								// trace( "state=" + state );
								
								if ( state == RTCPeerConnectionState.FAILED ){
									
									failed( "state->failed");
								}
							}
							
							@Override
							public void 
							onDataChannel(
								RTCDataChannel _channel )
							{
								channel = _channel;
								
								setupChannel( true );
							}
						});
				
			RTCSessionDescription desc = new RTCSessionDescription( RTCSdpType.OFFER, _sdp );

			peerConnection.setRemoteDescription(
				desc,
				new SetSessionDescriptionObserver(){
					
					@Override
					public void 
					onSuccess()
					{
					}
					
					@Override
					public void 
					onFailure(
						String arg )
					{
						failed( "setRemoteDescription failed: " + arg );
					}
				});
			
			connection_state = peerConnection.getConnectionState();
			
			RTCAnswerOptions options = new RTCAnswerOptions();
			
			peerConnection.createAnswer(
				options,
				new CreateSessionDescriptionObserver(){
					
					@Override
					public void 
					onSuccess(
						RTCSessionDescription desc )
					{
						peerConnection.setLocalDescription( 
							desc,
							new SetSessionDescriptionObserver(){
								
								@Override
								public void 
								onSuccess()
								{
									synchronized( peer_lock ){
										
										if ( listener_triggered ){
											
											return;
										}
										
										listener_triggered = true;
									}
									
									answer_listener.gotAnswer(
										new Answer()
										{
											@Override
											public String 
											getOfferID()
											{
												return( _remote_offer_id );
											}
											@Override
											public String 
											getSDP()
											{
												return( desc.sdp );
											}
										});
								}
								
								@Override
								public void 
								onFailure(
									String arg )
								{
									failed( "setLocalDescription failed: " + arg );
								}
							});
					}
					
					@Override
					public void 
					onFailure(
						String arg )
					{
						failed( "createAnswer failed: " + arg );
					}
				});
		}
		
		void
		setupChannel(
			boolean		incoming )
		{
			channel.registerObserver(
				new RTCDataChannelObserver()
				{
					Object	queue_lock = new Object();
					
					List<ByteBuffer> queue = new ArrayList<>();
										
					@Override
					public void 
					onStateChange()
					{
						RTCDataChannelState state = channel.getState();
						
						if ( state != RTCDataChannelState.CLOSING && state != RTCDataChannelState.CLOSED ){

							trace( "channel state->" + state + ", incoming=" + ( answer_listener != null ));
							
							if ( state == RTCDataChannelState.OPEN ){
								
								peerConnection.getStats(
									new RTCStatsCollectorCallback(){
										
										@Override
										public void 
										onStatsDelivered(
											RTCStatsReport report )
										{
											Map<String,RTCStats> stats = report.getStats();
											
											String remote_ip = null;

											try{
												for ( Map.Entry<String,RTCStats> entry: stats.entrySet()){
													
													RTCStats	info 		= entry.getValue();
													
													Map<String,Object> members = info.getMembers();
													
													if ( 	members.containsKey( "isRemote" ) && 
															members.containsKey( "candidateType" ) &&
															members.containsKey( "address" )){
														
														boolean isRemote = (Boolean)members.get( "isRemote" );
														
														String type = (String)members.get( "candidateType" );
														
														if ( isRemote && !type.equals("relay")){
															
															String address = (String)members.get( "address" );
															
															try{
																InetAddress ia = InetAddress.getByName( address );
																
																if ( 	ia.isLoopbackAddress() ||
																		ia.isLinkLocalAddress() ||
																		ia.isSiteLocalAddress() ){
																	
																}else{
																	
																		// prefer ipv4 over ipv6 for display purposes
																	
																	if ( ia instanceof Inet4Address ){
																		
																		remote_ip = address; 
																		
																	}else{
																		
																		if ( remote_ip == null ){
																			
																			remote_ip = address; 
																		}
																	}
																}
															}catch( Throwable e ){
															}
														}
													}
												}
												
												if ( remote_ip == null ){
													
													//System.out.println( "No remote IP: " + stats );
												}
											}catch( Throwable e ){
												
												Debug.out( e );
											}
											
											BridgePeer bp = new BridgePeer( remote_ip, incoming );
											
											synchronized( peer_lock ){
												
												if ( pc_destroyed ){
													
													return;
												}
												
												if ( bridge_peer != null ){
													
													Debug.out( "Bridge peer already allocated" );
													
													return;
													
												}else{
													
													bridge_peer = bp;
												}
											}
											
											peer_bridge.addPeer( bp );
											
											synchronized( queue_lock ){
												
												if ( queue != null ){
													
													for ( ByteBuffer buffer: queue ){
														
														peer_bridge.receive( bp, buffer );
													}
													
													queue = null;
												}
											}
										}
									});
							}
						}
					}
					
					@Override
					public void 
					onMessage(
						RTCDataChannelBuffer arg )
					{
						ByteBuffer buffer = arg.data;
						
						trace( "Receive from peer: " + buffer.remaining());

						synchronized( queue_lock ){
							
							if ( queue != null ){
								
								ByteBuffer bb = ByteBuffer.allocate( buffer.remaining());
								
								bb.put( buffer );
								
								bb.flip();
								
								queue.add( bb );
								
							}else{
	
								peer_bridge.receive( bridge_peer, buffer );
							}
						}
					}
					
					@Override
					public void 
					onBufferedAmountChange(
						long arg )
					{
						trace( "Buffered: " + arg );
					}
				});
		}
		
		void
		gotAnswer(
			String		sdp )
		{
			RTCSessionDescription desc = new RTCSessionDescription(RTCSdpType.ANSWER,sdp);
			
			peerConnection.setRemoteDescription(
				desc,
				new SetSessionDescriptionObserver(){
					
					@Override
					public void 
					onSuccess()
					{
					}
					
					@Override
					public void 
					onFailure(
						String arg )
					{
						failed( "setRemoteDescription failed: " + arg );
					}
				});
		}
		
		long
		getOfferID()
		{
			return( offer_id );
		}
		
		boolean
		hasFailed()
		{
			long now = SystemTime.getMonotonousTime();
			
			if ( listener_timeout != 0 && !listener_triggered ){
				
				if ( now > created_time + listener_timeout ){
					
					return( true );
				}
			}
						
			if ( now - created_time > 120*1000 ){
			
				if ( connection_state == RTCPeerConnectionState.NEW ){
					
					return( true );
				}
			}
			
			return( false );
		}
		
		void
		failed(
			String		str )
		{
			trace( str );
			
			async_dispatcher.dispatch(()->{;

				removePeer( this );
			});
		}
		
		void
		destroy()
		{
			boolean fire_listeners = false;
			
			synchronized( peer_lock ){
				
				if ( pc_destroyed ){
					
					return;
				}
				
				pc_destroyed = true;
				
				if ( !listener_triggered ){
				
					listener_triggered = true;
				
					fire_listeners = true;
				}
			}
			
			if ( bridge_peer != null ){
				
				bridge_peer.destroyInternal();
				
				peer_bridge.removePeer( bridge_peer );
			}
			
			if ( fire_listeners ){
				
				try{
					if ( offer_listener != null ){
						offer_listener.failed();
					}
					if ( answer_listener != null ){
						answer_listener.failed();
					}
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
			
			if ( channel != null ){

				channel.close();
			}
			
			peerConnection.close();
		}
		
		class 
		BridgePeer
			implements WebRTCPeer
		{
			private final String	remote_ip;
			private final boolean 	incoming;
			
			private volatile boolean bp_destroyed;
			
			BridgePeer(
				String		_remote_ip,
				boolean		_incoming )
			{
				remote_ip		= _remote_ip;
				incoming		= _incoming;
			}
			
			public byte[]
			getInfoHash()
			{
				return( info_hash );
			}
			
			public long
			getOfferID()
			{
				return( offer_id );
			}
			
			public String
			getRemoteIP()
			{
				return( remote_ip );
			}
			
			public boolean
			isIncoming()
			{
				return( incoming );
			}
			
			public void
		    sendPeerMessage(
		    	ByteBuffer		buffer )
		    	
		    	throws Throwable
		    {
				trace( "Send to peer: " + buffer.remaining() + ", buffered=" + channel.getBufferedAmount());
				
					// seems to need to be a direct buffer otherwise nothing gets sent :(
				
				ByteBuffer d = ByteBuffer.allocateDirect( buffer.remaining());
				
				d.put( buffer );
				
				d.flip();
				
				RTCDataChannelBuffer channel_buffer = new RTCDataChannelBuffer( d, true );
				
				channel.send( channel_buffer );
		    }
		    
			public boolean
			isDestroyed()
			{
				return( bp_destroyed );
			}
			
			void
			destroyInternal()
			{
				bp_destroyed = true;
			}
			
			@Override
			public void 
			destroy()
			{
				PeerConnection.this.destroy();
			}
		}
	}
}
