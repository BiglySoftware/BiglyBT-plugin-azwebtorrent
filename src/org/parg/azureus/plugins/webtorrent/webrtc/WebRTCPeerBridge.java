/*
 * Created on Jan 7, 2016
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


package org.parg.azureus.plugins.webtorrent.webrtc;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.ByteBuffer;


import java.util.*;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Debug;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import org.parg.azureus.plugins.webtorrent.WebTorrentPlugin;

import com.biglybt.core.networkmanager.admin.NetworkAdmin;
import com.biglybt.core.peermanager.messaging.bittorrent.BTHandshake;
import com.biglybt.core.proxy.AEProxyAddressMapper;
import com.biglybt.core.proxy.AEProxyFactory;

public class 
WebRTCPeerBridge 
{
	private static byte[] fake_header_and_reserved = new byte[1+19+8];

	static{
		fake_header_and_reserved[0] = (byte)19;
		
		System.arraycopy( BTHandshake.PROTOCOL.getBytes(),0,fake_header_and_reserved,1,19);
		
		byte[] reserved = new byte[]{0, 0, 0, 0, 0, (byte)16, 0, 0 }; 
		
		System.arraycopy( reserved,0,fake_header_and_reserved, 20, 8 );
	}
	
	private static WebRTCPeerBridge singleton;
	
	public static WebRTCPeerBridge
	getSingleton(
		WebTorrentPlugin		plugin )
	{
		synchronized( WebRTCPeerBridge.class ){
			
			if ( singleton == null ){
				
				singleton = new WebRTCPeerBridge( plugin );
			}
			
			return( singleton );
		}
	}
	
	private WebTorrentPlugin	plugin;
	
	private Map<WebRTCPeer,Connection>		peer_map = new HashMap<>();
	
	public
	WebRTCPeerBridge(
		WebTorrentPlugin		_plugin )
	{
		plugin = _plugin;
	}
	
	public void
	addPeer(
		WebRTCPeer		peer )
	{		
		Connection connection = new Connection( peer );
		
		synchronized( peer_map ){
		
			if ( peer.isDestroyed()){
				
				return;
			}
			
			peer_map.put( peer, connection );
			
			//System.out.println( "addPeer: " + peer.getOfferID() + ", peers=" + peer_map.size());

		}
		
		try{
			connection.start();
			
			
		}catch( Throwable e ){
			
			connection.destroy();
		}
	}
	
	public void
	removePeer(
		WebRTCPeer		peer )
	{		
		Connection connection;
		
		synchronized( peer_map ){
		
			connection = peer_map.remove( peer );
			
			if ( connection != null ){
			
				//System.out.println( "removePeer: " + peer.getOfferID() + ", peers=" + peer_map.size());
			}
		}
		
		if ( connection != null ){
			
			connection.destroy();
		}
	}
	
	public Map<String,Object>
	getInfo()
	{
		Map<String,Object>	result = new HashMap<>();
		
		List<Map<String,Object>>	peers = new ArrayList<>();
		
		result.put( "peers", peers );
		
		synchronized( peer_map ){
			
			for ( Connection c: peer_map.values()){
				
				peers.add( c.getInfo());
			}
		}
		
		return( result );
	}
	
	public void
	reset()
	{
		List<Connection>	connections;
	
		synchronized( peer_map ){

			connections = new ArrayList<>( peer_map.values());
			
			peer_map.clear();
		}
		
		for ( Connection c: connections ){
			
			try{
				
				c.destroy();
				
			}catch( Throwable e ){
				
			}
		}
	}
	
	public void
	receive(
		WebRTCPeer			peer,
		ByteBuffer			data )
	{
		//System.out.println( "receive: " + peer.getOfferID());
		
		Connection connection;
		
		synchronized( peer_map ){
		
			connection = peer_map.get( peer );
		}
		
		if ( connection != null ){
			
			connection.receive( data );
			
		}else{
			
			peer.destroy();
		}
	}
	
	
	private class
	Connection
	{
		private	WebRTCPeer							web_rtc_peer;
		private Socket								socket;
		
		private String								remote_ip;
		private Peer								peer;
		
		private AEProxyAddressMapper.PortMapping	mapping;
		
		AESemaphore		wait_sem = new AESemaphore( "" );
		
		long	total_sent	= 0;
		
		int	to_skip = 0;
		
		private
		Connection(
			WebRTCPeer		_web_rtc_peer )
		{
			web_rtc_peer	= _web_rtc_peer;
		}
		
		private Peer
		fixup()
		{
			synchronized( this ){
				
				if ( peer == null ){
					
					try{
						Download download = plugin.getPluginInterface().getDownloadManager().getDownload( web_rtc_peer.getInfoHash());
						
						if ( download != null ){
							
							PeerManager pm = download.getPeerManager();
							
							if ( pm != null ){
								
								Peer[] peers = pm.getPeers( remote_ip );
								
								if ( peers.length > 0 ){
									
									peer	= peers[0];
								}
							}
						}
					}catch( Throwable e ){
						
					}
				}
			}
			
			return( peer );
		}
		
		private void
		start()
		
			throws Exception
		{
			boolean	ok = false;
			
			try{
				socket = new Socket( Proxy.NO_PROXY );

				socket.bind( null );
				
				final int local_port = socket.getLocalPort();
				
					// we need to pass the peer_ip to the core so that it doesn't just see '127.0.0.1'
				
				remote_ip = web_rtc_peer.getRemoteIP();
								
				if ( remote_ip == null ){
					
					remote_ip = "Websocket." + local_port;
				}
				
				boolean incoming = web_rtc_peer.isIncoming();
				
				Map<String,Object>	props = new HashMap<String, Object>();
				
				props.put( AEProxyAddressMapper.MAP_PROPERTY_DISABLE_AZ_MESSAGING, true );
				props.put( AEProxyAddressMapper.MAP_PROPERTY_PROTOCOL_QUALIFIER, "WebSocket" );
				props.put( AEProxyAddressMapper.MAP_PROPERTY_CONNECTION_INCOMING, incoming );
					
				mapping = AEProxyFactory.getAddressMapper().registerPortMapping( local_port, remote_ip, props );
				
				InetAddress bind = NetworkAdmin.getSingleton().getSingleHomedServiceBindAddress();
				
				if ( bind == null || bind.isAnyLocalAddress()){
					
					bind = InetAddress.getByName( "127.0.0.1" );
				}
				
				socket.connect( new InetSocketAddress( bind, COConfigurationManager.getIntParameter( "TCP.Listen.Port" )));
			
				socket.setTcpNoDelay( true );
	
				if ( incoming ){
					
					OutputStream	os = socket.getOutputStream();
					
					os.write( fake_header_and_reserved );
					
					os.write( web_rtc_peer.getInfoHash());
					
					os.flush();
					
					to_skip = fake_header_and_reserved.length + 20;
					
				}
				
				final InputStream is = socket.getInputStream();
				
				new AEThread2( "WebSocket:pipe" )
				{
					public void
					run()
					{
						try{						
							while( true ){
								
								byte[]	buffer = new byte[16*1024];
	
								int	len = is.read( buffer );
								
								if ( len <= 0 ){
									
									break;
								}
								
								web_rtc_peer.sendPeerMessage( ByteBuffer.wrap( buffer, 0, len ));
								
								total_sent += len;
							}
						}catch( Throwable e ){
							
							// Debug.out( e );
							
						}finally{
							
							destroy();
						}
					}
				}.start();
				
				ok = true;
				
			}finally{
				
				wait_sem.releaseForever();
				
				if ( !ok ){
					
					destroy();
				}
			}
		}
		
		private void
		receive(
			ByteBuffer	data )
		{
			if ( !wait_sem.reserve(60*1000)){
				
				Debug.out( "eh?" );
				
				destroy();
				
				return;
			}
			
			if ( to_skip > 0 ){
				
				int	remaining = data.remaining();
				
				if ( to_skip <  remaining ){
					
					data.position( data.position() + to_skip );
					
					to_skip = 0;
					
				}else{
					
					to_skip -= remaining;
					
					return;
				}
			}
			
			try{
				OutputStream os = socket.getOutputStream();
				
				byte[]	buffer = new byte[data.remaining()];
				
				data.get( buffer );
				
				os.write( buffer );
				
				os.flush();
				
			}catch( Throwable e ){
				
				// Debug.out( e );
				
				destroy();
			}
		}
		
		private Map<String,Object>
		getInfo()
		{
			Map<String,Object>	result = new HashMap<>();
			
			result.put( "offer_id", String.valueOf( web_rtc_peer.getOfferID()));
			
			result.put( "ip", web_rtc_peer.getRemoteIP());
			
			Peer peer = fixup();
			
			if ( peer != null ){
				
				result.put( "client", peer.getClient());
				
				result.put( "seed", peer.isSeed());
				
				result.put( "percent", peer.getPercentDoneInThousandNotation()/10);
				
			}else{
				
				result.put( "client", "Unknown" );
				
				result.put( "seed", false );
				
				result.put( "percent", -1 );
			}
			
			return( result );
		}
		
		private void
		destroy()
		{
			try{
				if ( socket != null ){
				
					try{
						socket.close();
					
						socket = null;
						
					}catch( Throwable e ){
					}
				}
				
				if ( web_rtc_peer != null ){
					
					try{
						web_rtc_peer.destroy();
						
					}catch( Throwable e ){
						
					}
				}
				
				if ( mapping != null ){
					
					mapping.unregister();
					
					mapping = null;
				}
			}finally{
				
				synchronized( peer_map ){
					
					peer_map.remove( web_rtc_peer );
				}
			}
		}
	}
}
