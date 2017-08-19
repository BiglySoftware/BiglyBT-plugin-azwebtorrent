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


package org.parg.azureus.plugins.webtorrent.impl;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.glassfish.tyrus.server.Server;
import com.biglybt.core.util.Base32;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;

import com.biglybt.util.JSONUtils;

@ServerEndpoint("/vuze")
public class 
JavaScriptProxyInstance 
{
	private static List<Logger>	loggers = new ArrayList<Logger>();
	
	static{
		String[] logger_classes = {
				"java.lang.Class",		// someone messed up coding org.glassfish.tyrus.server.Server logger...
				"org.glassfish.grizzly.http.server.NetworkListener",
				"org.glassfish.grizzly.http.server.HttpServer",
		};
		
		for ( String name: logger_classes ){
			
			Logger logger = Logger.getLogger( name );
		
			logger.setLevel( Level.OFF );
	
			loggers.add( logger );
		}
	}
	
	private static Listener		listener;
	
    protected static ServerWrapper 
    startServer(
    	Listener		_listener ) 
    
    	throws Exception
    {
    	ClassLoader old_loader = Thread.currentThread().getContextClassLoader();
    
    	try{
    			// need to do this as the tyrus/grizzly stuff explicitly uses the context class
    			// loader to instantiate things and this fails if it isn't the plugin one
    		
    		Thread.currentThread().setContextClassLoader( JavaScriptProxyInstance.class.getClassLoader());
    	
	    	listener	= _listener;
	    	
	    	int[] potential_ports = new int[32];
	    	
	    	potential_ports[0] = 8025;		// default
	    	
	    	for ( int i=1;i<potential_ports.length;i++){
	    		
	    		potential_ports[i] = 2000 + (RandomUtils.nextAbsoluteInt()%60000);
	    	}
	    	
	    	Exception last_error = null;
	    	
	    	for ( int port: potential_ports ){
	    	
	    		try{
	    			Map<String,Object>	properties = new HashMap<>();
	    			
	    			Server server = new Server( "127.0.0.1", port, "/websockets", properties, JavaScriptProxyInstance.class);
	        
	    			server.start();
	    	
	    			return( new ServerWrapper( server, port ));
	    			
	    		}catch( DeploymentException e ){
	    			
	    			last_error = e;
	    		}
	    	}
	    	
	    	throw( last_error );
	    	
    	}finally{
    		
    		try{
    			Thread.currentThread().setContextClassLoader( old_loader );
    			
    		}catch( Throwable e ){
    			
    			Debug.out( e );
    		}
    	}
    }
    

    private long		instance_id;
    private long		offer_id;
    private boolean		is_peer;
    private String		remote_ip;
    private boolean		is_incoming;
    private byte[]		info_hash;
    
    private Session		session;
    private boolean		destroyed;
    
	protected long
	getInstanceID()
	{
		return( instance_id );
	}
	
	protected long
	getOfferID()
	{
		return( offer_id );
	}
	
	protected boolean
	isIncoming()
	{
		return( is_incoming );
	}
	
	protected String
	getRemoteIP()
	{
		return( remote_ip );
	}
	
	protected byte[]
	getInfoHash()
	{
		return( info_hash );
	}
	
	protected boolean
	isDestroyed()
	{
		return( destroyed );
	}
	
	@OnOpen
    public void 
    onOpen(
    	Session _session )
    			
    	throws IOException 
    {
		session = _session;
		
		//System.out.println( "onOpen: " + session );
		
		String query = session.getRequestURI().getQuery();
		
		String[] args = query.split( "&" );
		
		String		type 		= null;
		long		id			= -1;
		long		oid			= 0;
		boolean		inc			= false;
		byte[]		hash	 	= null;
		String		remote		= "";
		
		for ( String arg: args ){
			
			String[]	bits = arg.split( "=" );
			
			String lhs 	= bits[0].trim();
			String rhs	= bits.length==1?"":bits[1].trim();
			
			if ( lhs.equals( "type" )){
				
				type = rhs;
				
			}else if ( lhs.equals( "id" )){
				
				id = Long.parseLong( rhs );
				
			}else if ( lhs.equals( "offer_id" )){
				
				oid = Long.parseLong( rhs );
				
			}else if ( lhs.equals( "incoming" )){
				
				inc	= rhs.equals( "true" );		
			
			}else if ( lhs.equals( "hash" )){
					
				hash = Base32.decode( rhs );
				
			}else if ( lhs.equals( "remote" )){
				
				remote = rhs.length()==0?null:rhs;
			}
		}
		
		if ( type.equals( "control" ) && id >= 0 ){
			
			instance_id	= id;
			
			listener.controlCreated( this );
			
		}else if ( type.equals( "peer" )){

			instance_id	= id;
			offer_id	= oid;
			is_peer		= true;
			is_incoming	= inc;
			info_hash	= hash;
			
			if ( remote != null ){
				
				String[] ips = remote.split(",");
								
				for ( String ip: ips ){
					
					try{
						InetAddress ia = InetAddress.getByName( ip );
						
						if ( 	ia.isLoopbackAddress() ||
								ia.isLinkLocalAddress() ||
								ia.isSiteLocalAddress() ){
							
						}else{
							
								// prefer ipv4 over ipv6 for display purposes
							
							if ( ia instanceof Inet4Address ){
								
								remote_ip = ip; 
								
							}else{
								
								if ( remote_ip == null ){
									
									remote_ip = ip; 
								}
							}
						}
					}catch( Throwable e ){
					}
				}
				
				
			}
			
			listener.peerCreated( this );
		}
    }

    @OnMessage
    public String 
    onMessage(
    	String message) 
    {
    	Map<String,Object> map = JSONUtils.decodeJSON( message );
    	
    	Map<String,Object> result = listener.receiveControlMessage( this, map );
    	
    	return( JSONUtils.encodeToJSON( result ));
    }

    @OnMessage
    public void 
    onMessage(
    	ByteBuffer 	message ) 
    {
    	 listener.receivePeerMessage( this, message );
    }
    	
    protected void
    sendControlMessage(
    	Map<String,Object>			message )
    	
    	throws Throwable
    {
    	if ( isDestroyed()){
    		
    		throw( new Exception( "Destroyed" ));
    	}
    	
    	try{
    		session.getBasicRemote().sendText( JSONUtils.encodeToJSON( message ));
    		
    	}catch( Throwable e ){
    		
    		onError( e );
    		
    		throw( e );
    	}
    }
    
    protected void
    sendPeerMessage(
    	ByteBuffer		buffer )
    	
    	throws Throwable
    {
    	if ( isDestroyed()){
    		
    		throw( new Exception( "Destroyed" ));
    	}
    	
    	try{
    		session.getBasicRemote().sendBinary( buffer );
    		
    	}catch( Throwable e ){
    		
    		onError( e );
    		
    		throw( e );
    	}
    }
    
    @OnError
    public void 
    onError(
    	Throwable e ) 
    {
    	Debug.out( e );
    	
    	destroy();
    	
    	if ( is_peer ){
    		
    		listener.peerDestroyed( this );
    		
    	}else{
    		
    		listener.controlDestroyed( this );
    	}
    }

    @OnClose
    public void
    onClose(
    	Session session) 
    {
    	destroyed	= true;
    	
    	if ( is_peer ){
    		
    		listener.peerDestroyed( this );
    		
    	}else{
    		
    		listener.controlDestroyed( this );
    	}
    }
    
    protected void
    destroy()
    {
    	destroyed	= true;
    	
    	try{
    		session.close();
    		
    	}catch( Throwable e ){
    		
    		Debug.out( e );
    	}
    }
    
    protected static class
    ServerWrapper
    {
    	private final Server			server;
    	private final int				port;
    	
    	private
    	ServerWrapper(
    		Server		_server,
    		int			_port )
    	{
    		server		= _server;
    		port		= _port;
    	}
    	
    	public int
    	getPort()
    	{
    		return( port );
    	}
    	
    	public void
    	destroy()
    	{
    		try{
    			server.stop();
    			
    		}catch( Throwable e ){
    			
    			Debug.out( e );
    		}
    	}
    }
    
    protected interface
    Listener
    {
    	public void
    	controlCreated(
    		JavaScriptProxyInstance		inst );
    	
    	public Map<String,Object>
    	receiveControlMessage(
    		JavaScriptProxyInstance		inst,
    		Map<String,Object>			message );
    	
    	public void
    	controlDestroyed(
    		JavaScriptProxyInstance		inst );
    	
       	public void
    	peerCreated(
    		JavaScriptProxyInstance		inst );
    	
    	public void
    	receivePeerMessage(
    		JavaScriptProxyInstance		inst,
    		ByteBuffer					message );
    	
    	public void
    	peerDestroyed(
    		JavaScriptProxyInstance		inst );
    }
}
