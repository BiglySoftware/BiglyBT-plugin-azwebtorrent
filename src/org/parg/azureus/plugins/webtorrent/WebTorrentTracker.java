/*
 * Created on Feb 5, 2016
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.HashWrapper;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.pifimpl.local.ipc.IPCInterfaceImpl;
import com.biglybt.util.JSONUtils;
import org.json.simple.JSONObject;
import org.parg.azureus.plugins.webtorrent.GenericWSServer.ServerWrapper;


public class 
WebTorrentTracker 
{
	private static boolean	TRACE = false;
	
	private final WebTorrentPlugin		plugin;
	
	private final GenericWSServer		gs_server;
	
	private final boolean				is_ssl;
	private final String				bind_ip;
	private final int					port;
	
	private final String				host;
	
	private final Object		lock = new Object();
	
	private ServerWrapper		server;
	
	private Map<Object,SessionWrapper>		session_map 	= new HashMap<>();
	
	private Map<HashWrapper,TrackedTorrent>	active_torrents	= new HashMap<>();
	
	private final TimerEventPeriodic	timer;
	
	private boolean		destroyed;
	
	protected
	WebTorrentTracker(
		WebTorrentPlugin	_plugin,
		GenericWSServer		_server,
		boolean				_is_ssl,
		String				_bind_ip,
		int					_port,
		String				_host )
	{
		plugin		= _plugin;
		gs_server	= _server;
		is_ssl		= _is_ssl;
		bind_ip		= _bind_ip;
		port		= _port;
		host		= _host;
		
		timer = 
			SimpleTimer.addPeriodicEvent(
				"WTT:timer",
				20*1000,
				new TimerEventPerformer() {
					
					@Override
					public void 
					perform(
						TimerEvent event ) 
					{
						synchronized( lock ){
							
							for ( TrackedTorrent tt: active_torrents.values()){
								
								tt.checkTimeouts();
							}
							
							plugin.updateTrackerStatus( session_map.size(), active_torrents.size());
						}
						
						if ( TRACE ){
							
							print();
						}
					}
				});
	}
	
	public boolean
	isSSL()
	{
		return( is_ssl );
	}
	
	public int
	getPort()
	{
		return( port );
	}
	
	public String
	getBindIP()
	{
		return( bind_ip );
	}

	public String
	getHost()
	{
		return( host );
	}
	
	public void
	start()
	
		throws Exception
	{
		server = gs_server.startServer( is_ssl, bind_ip, port, "/wstracker", new IPCInterfaceImpl( this ));
	}
	
	public void
	stop()
	{
		server.destroy();
		
		timer.cancel();
	}
	
	public String
	getURL()
	{
		return( ( is_ssl?"wss":"ws" ) + "://" + host + ":" + port + "/wstracker/vuze" );
	}
	
	private void
	trace(
		String		str )
	{
		if ( TRACE ){
			System.out.println( str );
		}
	}
	
	private void
	print()
	{
		synchronized( lock ){
			
			System.out.println( "WebTorrentTracker: sessions=" + session_map.size() + ", torrents=" + active_torrents.size());
			
			System.out.println( "Sessions" );
			
			for ( SessionWrapper sw: session_map.values()){
				
				sw.print();
			}
			
			System.out.println( "Torrents" );
			
			for (TrackedTorrent tt: active_torrents.values()){
				
				tt.print();
			}
		}
	}
	
	public void
	sessionAdded(
		Object		server,
		URI			uri,
		Object		session )
	{
		boolean	close_it = false;
		
		synchronized( lock ){
			
			if ( destroyed ){
				
				close_it = true;
				
			}else{
			
				session_map.put( session, new SessionWrapper( server,  session, uri ));
			}
		}
		
		if ( close_it ){
			
			try{
				plugin.closeSession( server, session );
				
			}catch( Throwable f ){
				
			}
		}
	}
	
	public void
	sessionRemoved(
		Object		server,
		Object		session )
	{
		SessionWrapper	wrapper;
		
		synchronized( lock ){
			
			wrapper = session_map.remove( session );
		}
		
		if ( wrapper != null ){
			
			wrapper.destroy();
		}
	}
	
	private SessionTorrent
	getSessionTorrent(
		SessionWrapper	sw,
		byte[]			hash )
	{
		synchronized( lock ){
		
			HashWrapper	hw = new HashWrapper( hash );
			
			TrackedTorrent tt = active_torrents.get( hw );
			
			if ( tt == null ){
				
				tt = new TrackedTorrent( hw );
				
				active_torrents.put( hw, tt );
			}
			
			return( sw.getSessionTorrent( tt ));
		}
	}
	
	private int
	getOptionalInt(
		Map<String,Object>		map,
		String					key,
		int						def )
	{
		Number number = (Number)map.get( key );
		
		return( number==null?def:number.intValue());
	}
	
	private long
	getOptionalLong(
		Map<String,Object>		map,
		String					key,
		long					def )
	{
		Number number = (Number)map.get( key );
		
		return( number==null?def:number.longValue());
	}
	
	public void
	messageReceived(
		Object		server,
		Object		session,
		String		message )
	{
		trace( "messageReceived: " + message );
		
		boolean	close_it	= false;
		
		String	reply 		= null;
		
		synchronized( lock ){
			
			SessionWrapper wrapper = session_map.get( session );
		
			if ( wrapper == null ){
				
				close_it = true;
				
			}else{
			
				wrapper.setAlive();
				
				try{			
					Map<String, Object> map_in	= JSONUtils.decodeJSON( message );
					
					String hash_str = (String)map_in.get( "info_hash" );
								
					byte[] info_hash = hash_str.getBytes( "ISO-8859-1" );
					
					SessionTorrent	session_torrent = getSessionTorrent( wrapper, info_hash );			
					
					TrackedTorrent tracked_torrent = session_torrent.getTrackedTorrent();

					String peer_id_str = (String)map_in.get( "peer_id" );
					
					byte[] peer_id = peer_id_str.getBytes( "ISO-8859-1" );

					if ( map_in.containsKey( "answer" )){
						
						String 	offer_id_str	= (String)map_in.get( "offer_id" );
												
						byte[] offer_id = offer_id_str.getBytes( "ISO-8859-1" );
						
						SessionTorrent target_session = tracked_torrent.lookupSessionForOffer( offer_id );
						
						if ( target_session != null ){
								
							target_session.getSessionWrapper().sendMessage( message );
						}
					}else{
						int		numwant 	= getOptionalInt( map_in, "numwant", 0 );
						long	uploaded 	= getOptionalLong( map_in, "uploaded", 0 );
						long	downloaded 	= getOptionalLong( map_in, "downloaded", 0 );
						
							// meh, not present for webtorrent
						
						long	left 		= getOptionalLong( map_in, "left", Long.MAX_VALUE );
						
							// meh, they changed to use "announce" and "scrape"
						
						boolean	is_scrape;
						boolean	is_new_action;
						
						String action_str = (String)map_in.get( "action" );
						
						if ( action_str != null ){
							
							is_new_action	= true;
							
							is_scrape 		= action_str.equals( "scrape" );
							
						}else{
						
							int		action 		= getOptionalInt( map_in, "action", 0 );
												
							is_scrape = action == 2;
							
							is_new_action = false;
						}
						
						if ( left == 0 && !is_scrape ){
														
							session_torrent.setComplete();
						}
						
						String event = (String)map_in.get( "event" );
						
						if ( event != null ){
							
							if ( event.equals( "completed" )){
														
								session_torrent.setComplete();	
							}
						}
						
						List<Map>	offers = (List<Map>)map_in.get( "offers" );
						
						if ( offers != null ){
							
							for ( Map m: offers ){
								
								Map	 	offer 			= (Map)m.get( "offer" );
								String 	offer_id_str	= (String)m.get( "offer_id" );
								
								String offer_sdp	= (String)offer.get( "sdp" );
								
								byte[] offer_id = offer_id_str.getBytes( "ISO-8859-1" );
								
								String offer_message = "{\"offer\":{\"type\":\"offer\",\"sdp\":\"" + 
										WebTorrentPlugin.encodeForJSON( offer_sdp  ) + "\"}," + 
										"\"offer_id\":\"" + WebTorrentPlugin.encodeForJSON( offer_id ) + "\"," + 
										"\"peer_id\":\"" + WebTorrentPlugin.encodeForJSON( peer_id ) + "\"," + 
										"\"info_hash\":\"" + WebTorrentPlugin.encodeForJSON( info_hash ) + "\"" + 
										"}";
								
								SessionTorrent target_session = tracked_torrent.allocateSessionForOffer( session_torrent, offer_id, offer_message );
								
								if ( target_session != null ){
										
									target_session.getSessionWrapper().sendMessage( offer_message );	
								}
							}
						}
					
						Map<String, Object> map_out = new JSONObject();
												
						int complete 	= tracked_torrent.getComplete();
						int incomplete 	= tracked_torrent.getIncomplete();
						
						if ( is_scrape ){
							// adjust!
							
							if ( session_torrent.isComplete()){
								
								complete--;
								
							}else{
								
								incomplete--;
							}
						}
						
						if ( is_new_action ){
							
							map_out.put( "action", is_scrape?"scrape":"announce" );

							if ( is_scrape ){
								
								JSONObject files = new JSONObject();
								
								map_out.put( "files", files );
								
								JSONObject file = new JSONObject();
								
								files.put( WebTorrentPlugin.encodeForJSON( info_hash ), file );
								
								file.put( "complete", Math.max(complete,0));
								file.put( "incomplete", Math.max(incomplete,0));
								file.put( "downloaded", Math.max(complete,0));	// TODO!

								JSONObject flags = new JSONObject();
								
								map_out.put( "flags", flags );
								
								flags.put( "min_request_interval", 600 );
								
							}else{
								
								map_out.put( "info_hash", WebTorrentPlugin.encodeForJSON( info_hash ));

								map_out.put( "complete", Math.max(complete,0));
								map_out.put( "incomplete", Math.max(incomplete,0));
								
								
								map_out.put( "interval", 120 );
							}
						}else{
							
							map_out.put( "info_hash", WebTorrentPlugin.encodeForJSON( info_hash ));

							map_out.put( "complete", Math.max(complete,0));
							map_out.put( "incomplete", Math.max(incomplete,0));
							
							map_out.put( "action", is_scrape?2:1 );
							
							map_out.put( "interval", 120 );
						}
						
						reply = JSONUtils.encodeToJSON( map_out );
						
						reply = reply.replaceAll( "\\\\u00", "\\u00" );
						
						if ( event != null ){
							
							if ( event.equals( "stopped" )){
								
								wrapper.removeSessionTorrent( session_torrent );
							}
						}
					}
				}catch( Throwable e ){
					
					close_it = true;
					
				}finally{
			
					if ( close_it ){
						
						wrapper.destroy();
						
						session_map.remove( session );
					}
				}
			}
		}
		
		if ( reply != null ){
			
			try{
				
				plugin.sendMessage( server, session, reply );
				
			}catch( Throwable f ){
				
				close_it	= true;
			}	
		}
			
		if ( close_it ){
			
			try{
				plugin.closeSession( server, session );
				
			}catch( Throwable f ){
				
			}
		}
	}
	
	public void
	destroy()
	{
		if ( server != null ){
			
			server.destroy();
		}
		
		Collection<SessionWrapper>	to_destroy;
		
		synchronized( lock ){
			
			destroyed	= true;
			
			to_destroy = session_map.values();
			
			session_map.clear();
		}
		
		for ( SessionWrapper sw: to_destroy ){
			
			sw.destroy();
		}
	}
	
	private class
	SessionWrapper
	{
		private final Object		server;
		private final Object		session;
		private final URI			uri;
		
		private long	last_heard_from;
				
		private Map<HashWrapper, SessionTorrent>	session_torrents = new HashMap<>();
		
		private
		SessionWrapper(
			Object		_server,
			Object		_session,
			URI			_uri )
		{
			server		= _server;
			session		= _session;
			uri			= _uri;
			
			setAlive();
		}
		
		private String
		getName()
		{
			return( "SW: " + String.valueOf( session ));
		}
		
		private void
		setAlive()
		{
			last_heard_from = SystemTime.getMonotonousTime();
		}
		
		private SessionTorrent
		getSessionTorrent(
			TrackedTorrent		tt )
		{
			synchronized( lock ){
				
				HashWrapper	hw = tt.getHash();
				
				SessionTorrent st = session_torrents.get( hw );
				
				if ( st == null ){
					
					st = new SessionTorrent( this, tt );
					
					session_torrents.put( hw, st );
				}
				
				return( st );
			}
		}
		
		private void
		removeSessionTorrent(
			SessionTorrent		st )
		{
			synchronized( lock ){
				
				if ( session_torrents.remove( st.getTrackedTorrent().getHash()) != null ){
				
					st.destroy();
				}
			}
		}
		
		private void
		sendMessage(
			String			message )
			
			throws Exception
		{
			plugin.sendMessage( server, session, message );	
		}
		
		private void
		destroy()
		{
			synchronized( lock ){
				
				for ( SessionTorrent st: session_torrents.values()){
					
					st.destroy();
				}
				
				session_torrents.clear();
			}
		}
		
		private void
		print()
		{
			System.out.println( "    " + getName() + ", STs=" + session_torrents.size());
			
			for (SessionTorrent st: session_torrents.values()){
				
				st.print();
			}
		}
	}
	
	private class
	SessionTorrent
	{
		private final SessionWrapper	session_wrapper;
		private final TrackedTorrent	tracked_torrent;
		
		private Map<SessionTorrent,OutstandingOffer>	oo_map = new HashMap<>();
		
		private boolean	complete;
		private boolean	destroyed;
		
		private
		SessionTorrent(
			SessionWrapper		_sw,
			TrackedTorrent		_tt )
		{
			session_wrapper	= _sw;
			tracked_torrent	= _tt;
			
			tracked_torrent.addSessionTorrent( this );
			
			trace( "ST added: " + getName());
		}
		
		private void
		setComplete()
		{
			if ( !complete){
			
				complete = true;
				
				tracked_torrent.setComplete( this );
			}
		}
		
		private boolean
		isComplete()
		{
			return( complete );
		}
		
		private TrackedTorrent
		getTrackedTorrent()
		{
			return( tracked_torrent );
		}
		
		private SessionWrapper
		getSessionWrapper()
		{
			return( session_wrapper );
		}
		
		private void
		addOutstandingOffer(
			OutstandingOffer		oo )
		{
			synchronized( lock ){
				
				oo_map.put( oo.getFromST(), oo );
			}
		}
		
		private void
		removeOutstandingOffer(
			OutstandingOffer		oo )
		{
			synchronized( lock ){
				
				oo_map.remove( oo.getFromST());
			}
		}
		
		private boolean
		hasOutstandingOffer(
			SessionTorrent		from_session )
		{
			synchronized( lock ){
				
				return( oo_map.containsKey( from_session ));
			}
		}
		
		private boolean
		isDestroyed()
		{
			return( destroyed );
		}
		
		private String
		getName()
		{
			return( tracked_torrent.getName() + "/" + session_wrapper.getName());
		}
		
		private void
		destroy()
		{
			destroyed = true;
			
			tracked_torrent.removeSessionTorrent( this );
			
			trace( "ST removed: " + getName());

		}
		
		private void
		print()
		{
			System.out.println( "        " + getName() + ": oos=" + oo_map.size());
		}
	}
	
	private class
	TrackedTorrent
	{
		private final HashWrapper		hash;
		
		private final List<SessionTorrent>	session_torrents = new ArrayList<>();
				
		private final Map<HashWrapper,OutstandingOffer>	outstanding_offer_map = new HashMap<>();
				
		private int	st_offer_alloc_index;

		
		private int	total_incomplete;
		private int	total_complete;
		
		private
		TrackedTorrent(
			HashWrapper		_hw )
		{
			hash	= _hw;
			
			trace( "TT added: " + getName());
		}
		
		private HashWrapper
		getHash()
		{
			return( hash );
		}
		
		private String
		getName()
		{
			return( ByteFormatter.encodeString( hash.getBytes()));
		}
		
		private SessionTorrent
		allocateSessionForOffer(
			SessionTorrent	source,
			byte[]			offer_id,
			String			offer_message )
		{			
			boolean	source_complete = source.isComplete();
			
			synchronized( lock ){
			
				int num_st = session_torrents.size();
				
				for ( int i=st_offer_alloc_index;i<st_offer_alloc_index+num_st;i++){
					
					int	index = i%num_st;
					
					SessionTorrent	target = session_torrents.get( index );
					
					if ( source != target ){
						
						if ( !( source_complete && target.isComplete())){
							
							if ( !target.hasOutstandingOffer( source )){
								
								OutstandingOffer oo = new OutstandingOffer( source, target );
																
									// not great but at least moves things around
								
								st_offer_alloc_index = index+1;
																
								outstanding_offer_map.put( new HashWrapper( offer_id), oo );
							
								trace( "allocateSFO" );

								trace( "    -> " +  target.getName());

								return( target );
							}
						}
					}
				}
			}
			
				// TODO: could keep a pending pool of unused offers
			
			return( null );
		}
		
		private SessionTorrent
		lookupSessionForOffer(
			byte[]		offer_id )
		{
			trace( "lookupSFO" );
			
			synchronized( lock ){
				
				OutstandingOffer oo = outstanding_offer_map.remove( new HashWrapper( offer_id ));
				
				if ( oo != null ){
					
					SessionTorrent st = oo.getFromST();
					
					if ( !st.isDestroyed()){
						
						oo.destroy();
						
						trace( "    -> " +  st.getName());
						
						return( st );
					}
					
				}
			}
			
			return( null );
		}
		
		private void
		checkTimeouts()
		{
			for ( Iterator<Map.Entry<HashWrapper,OutstandingOffer>> it = outstanding_offer_map.entrySet().iterator();it.hasNext();){
				
				Map.Entry<HashWrapper,OutstandingOffer> entry = it.next();
				
				OutstandingOffer oo = entry.getValue();
				
				if ( oo.isExpired()){
					
					oo.destroy();
					
					it.remove();
				}
			}
		}
		
		private void
		addSessionTorrent(
			SessionTorrent		st )
		{
			synchronized( lock ){
				
				session_torrents.add( st );
				
				if ( st.isComplete()){
					
					total_complete++;
					
				}else{
					
					total_incomplete++;
				}
			}
		}
		
		private void
		setComplete(
			SessionTorrent		st )
		{
			total_complete++;
			
			total_incomplete--;
		}
		
		private int
		getComplete()
		{
			return( total_complete );
		}
		
		private int
		getIncomplete()
		{
			return( total_incomplete );
		}
		
		private void
		removeSessionTorrent(
			SessionTorrent		st )
		{
			synchronized( lock ){
			
				if ( session_torrents.remove( st )){
				
					if ( st.isComplete()){
						
						total_complete--;
						
					}else{
						
						total_incomplete--;
					}
					
					if ( session_torrents.size() == 0 ){
						
						destroy();
					}
				}
			}
		}
		
		private void
		destroy()
		{
			synchronized( lock ){
				
				active_torrents.remove( hash );
				
				trace( "TT removed: " + getName());
			}
		}
		
		private void
		print()
		{
			System.out.println( "    TT: " + getName());
			
			System.out.println( "    STs: " + session_torrents.size());
			
			for ( SessionTorrent st: session_torrents ){
				
				st.print();
			}
			
			System.out.println( "    OOs: " + outstanding_offer_map.size());

			for ( OutstandingOffer oo: outstanding_offer_map.values()){
				
				oo.print();
			}
		}
	}
	
	private class
	OutstandingOffer
	{
		private final SessionTorrent		from_st;
		private final SessionTorrent		to_st;
		
		private final long					create_time = SystemTime.getMonotonousTime();
		
		private
		OutstandingOffer(
			SessionTorrent		_from,
			SessionTorrent		_to )
		{
			from_st		= _from;
			to_st		= _to;
			
			to_st.addOutstandingOffer( this );

		}
		
		private SessionTorrent
		getFromST()
		{
			return( from_st );
		}
		
		private SessionTorrent
		getToST()
		{
			return( to_st );
		}
		
		public boolean
		isExpired()
		{
			return( SystemTime.getMonotonousTime() - create_time > 60*1000 );
		}
		
		private void
		destroy()
		{
			to_st.removeOutstandingOffer( this );
		}
		
		private void
		print()
		{
			System.out.println( "    " + from_st.getName() + " -> " + to_st.getName());
		}
	}
}
