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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import com.biglybt.core.logging.LogAlert;
import com.biglybt.core.logging.Logger;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.FileUtil;
import com.biglybt.core.util.ThreadPool;
import com.biglybt.core.util.ThreadPoolTask;
import com.biglybt.core.util.UrlUtils;

import com.biglybt.util.JSONUtils;

public class 
LocalWebServer 
{
		// http://olegh.ftp.sh/public-stun.txt

	private static String[] ice_urls = {
		"stun:stun.l.google.com:19302",
		"stun:stun1.l.google.com:19302",
		"stun:stun2.l.google.com:19302",
		"stun:stun3.l.google.com:19302",
		"stun:stun4.l.google.com:19302",
	};

	
	private String		ws_config;
	
	private long		instance_id;
	private Listener	listener;
	private int			filter_port;
	
	private ServerSocket server_socket;
	
	private volatile boolean	destroyed;
	
	public
	LocalWebServer(
		long		_instance_id,
		int			_ws_port,
		Listener	_listener )
		
		throws Exception
	{
		instance_id		= _instance_id;
		listener		= _listener;
		
		Map<String,Object> config_map = new HashMap<>();
		
		config_map.put( "id", String.valueOf( instance_id ));
		config_map.put( "url_prefix", "ws://127.0.0.1:" + _ws_port + "/websockets/vuze" );
		
		Map<String,Object>	peer_config = new HashMap<>();
		
		config_map.put( "peer_config", peer_config );
		
		List<Map<String,Object>>	ice_servers = new ArrayList<>();
		
		peer_config.put( "iceServers", ice_servers);
		
			// apparently 'urls' is the new way of doing this - use both for the moment
		
		List<String>	urls = new ArrayList<>();

		for ( String url: ice_urls ){
			
			urls.add( url );
			
			Map<String,Object> m = new HashMap<>();
			
			ice_servers.add( m );
			
			m.put( "url", url );
		}
		
		Map<String,Object>	urls_map = new HashMap<>();
		
		urls_map.put( "urls", urls );
		
		ice_servers.add( urls_map );
		
		ws_config = JSONUtils.encodeToJSON( config_map );
		
		create();
	}
	
	protected int
	getPort()
	{
		return( filter_port );
	}
	
	private void
	create()
	
		throws Exception
	{
		final ThreadPool thread_pool = new ThreadPool( "WebSocketPlugin", 32 );
		
	  	String	connect_timeout = System.getProperty("sun.net.client.defaultConnectTimeout"); 
	  	String	read_timeout 	= System.getProperty("sun.net.client.defaultReadTimeout"); 
	  			
	  	int	timeout = Integer.parseInt( connect_timeout ) + Integer.parseInt( read_timeout );
		
		thread_pool.setExecutionLimit( timeout );
	
		server_socket = new ServerSocket( 0, 1024, InetAddress.getByName("127.0.0.1"));
		
		filter_port	= server_socket.getLocalPort();
		
		server_socket.setReuseAddress(true);
						
		new AEThread2("WebSocketPlugin::filterloop")
		{
			public void
			run()
			{
				long	failed_accepts		= 0;

				while( true ){
					
					if ( destroyed ){
						
						break;
					}
					
					try{				
						Socket socket = server_socket.accept();
								
						failed_accepts = 0;
						
						thread_pool.run( new HttpFilter( socket ));
						
					}catch( Throwable e ){
						
						failed_accepts++;
												
						if ( failed_accepts > 10  ){

								// looks like its not going to work...
								// some kind of socket problem
											
							Logger.logTextResource(new LogAlert(LogAlert.UNREPEATABLE,
								LogAlert.AT_ERROR, "Network.alert.acceptfail"),
								new String[] { "" + filter_port, "TCP" });
												
							break;
						}
					}
				}
			}
		}.start();
	}
	
	public void
	destroy()
	{
		destroyed = true;
		
		if ( server_socket != null ){
			
			try{
				server_socket.close();
				
			}catch( Throwable e ){
			}
			
			server_socket = null;
		}
	}
	
	private class
	HttpFilter
		extends ThreadPoolTask
	{
		private final String NL = "\r\n";
		
		private Socket		socket;
		
		protected
		HttpFilter(
			Socket		_socket )
		{
			socket	= _socket;
		}
		
		public void
		runSupport()
		{
			try{						
				setTaskState( "reading header" );
										
				InputStream	is = socket.getInputStream();
				
				byte[]	buffer = new byte[1024];
				
				String	header = "";
				
				while(true ){
						
					int	len = is.read(buffer);
						
					if ( len == -1 ){
					
						break;
					}
									
					header += new String( buffer, 0, len, Constants.BYTE_ENCODING );
									
					if ( 	header.endsWith( NL+NL ) ||
							header.indexOf( NL+NL ) != -1 ){
						
						break;
					}
				}
				
				List<String>	lines = new ArrayList<String>();
				
				int	pos = 0;
				
				while( true){
					
					int	p1 = header.indexOf( NL, pos );
					
					String	line;
					
					if ( p1 == -1 ){
						
						line = header.substring(pos);
						
					}else{
											
						line = header.substring( pos, p1 );
					}
					
					line = line.trim();
					
					if ( line.length() > 0 ){
					
						lines.add( line );
					}
				
					if ( p1 == -1 ){
						
						break;
					}
					
					pos = p1+2;
				}
				
				if ( lines.size() == 0 ){
					
					throw( new IOException( "No request" ));
				}
				
				String[]	lines_in = new String[ lines.size()];
				
				lines.toArray( lines_in );
				
				String	get = lines_in[0];
				
				//System.out.println( "Got request: " + get );
				
				String resource 		= null;
				String content_type 	= null;
				
				pos = get.indexOf( ' ' );
				
				String url = get.substring( pos+1 ).trim();
				
				pos = url.lastIndexOf( ' ' );
				
				url = url.substring( 0,  pos ).trim();

				if ( url.startsWith( "/index.html?id=" + instance_id )){
					
					resource 		= "index.html";
					content_type	= "text/html";
					
				}else if ( url.equals( "/script.js" ) || url.equals( "/torrentgraph.js" ) || url.equals( "/d3.js" )){
					
					resource 		= url.substring(1);
					content_type	= "application/javascript;charset=UTF-8";
					
				}else if ( url.endsWith( ".png" )){
					
					resource 		= url.substring(1);
					content_type	= "image/png";
					
				}else if ( url.equals( "/favicon.ico" )){

					resource 		= "favicon.ico";
					content_type	= "image/x-icon";
				}
				
				OutputStream	os = socket.getOutputStream();

				if ( resource != null ){
					
					InputStream res = getClass().getResourceAsStream( "/org/parg/azureus/plugins/webtorrent/resources/" + resource );
					
					byte[] bytes = FileUtil.readInputStreamAsByteArray( res );
					
					try{
						
						os.write( 
							( 	"HTTP/1.1 200 OK" + NL +
								"Content-Type: " + content_type + NL + 
								"Set-Cookie: vuze-ws-config=" + ws_config + "; path=/" + NL +
								"Connection: close" + NL +
								"Content-Length: " + bytes.length + NL + NL ).getBytes());
						
						os.write( bytes );
						
						os.flush();						
						
					}finally{
						
						res.close();
					}
				}else{
										
					if ( url.startsWith( "/?target" )){
						
						String original_url = UrlUtils.decode( url.substring( url.indexOf('=') + 1 ));
						
						listener.handleRequest( original_url, os );
					}
				}
			}catch( Throwable e ){
								
			}finally{
				
				try{
					socket.close();
					
				}catch( Throwable f ){
					
				}
			}
		}
		
		public void
		interruptTask()
		{
			try{	
				socket.close();
				
			}catch( Throwable e ){
				
			}
		}
	}
	
	public interface 
	Listener
	{
		public void
		handleRequest(
			String			url,
			OutputStream	os )
			
			throws Exception;
			
	}
}
