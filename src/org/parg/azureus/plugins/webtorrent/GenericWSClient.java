/*
 * Created on Feb 3, 2016
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

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.Debug;

public class 
GenericWSClient 
{
	protected
	GenericWSClient()
	{
		
	}
	
	public InputStream
	connect(
		URL						ws_url,
		Map<String,Object>		options,
		Map<String,Object>		result )
		
		throws Exception
	{
		Number	connect_timeout = (Number)options.get( "connect_timeout" );
		
		Number	read_timeout 	= (Number)options.get( "read_timeout" );

		if ( read_timeout == null ){
			
			read_timeout = new Integer( 60*1000 );
		}
		
        ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
        
        ClientManager client = ClientManager.createClient();
        	
        if ( connect_timeout != null ){
        
        	client.getProperties().put( ClientProperties.HANDSHAKE_TIMEOUT, new Integer( connect_timeout.intValue()));
        }
        
        final WSInputStream input_stream = new WSInputStream(read_timeout==null?0:read_timeout.intValue());
        	
        final Session session = 
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
	                    	input_stream.initialise( session );
	                    	
	                        session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {

	                            @Override
	                            public void 
	                            onMessage(
	                            	ByteBuffer message ) 
	                            {
	                            	input_stream.write( message );
	                            }
	                        });
	                        
	                    }catch( Exception e ){
	                    	
	                    	input_stream.error( e );
	                    	
	                    	Debug.out( e );
	                    }
	                }
	                
	                @Override
	                public void 
	                onClose(
	                	Session 		session, 
	                	CloseReason 	closeReason )
	                {
	                	input_stream.close();
	                }
	                
	                @Override
	                public void 
	                onError(
	                	Session 	session, 
	                	Throwable 	e )
	                {
                		input_stream.error( e );
	                }
        		}, cec, ws_url.toURI());

        result.put( "input_stream", input_stream );
        
        result.put( "output_stream", new WSOutputStream( session ));
        
        return( input_stream );
	}
	
	private class
	WSInputStream
		extends InputStream
	{
		private int			read_timeout;
		private	Session		session;
		
		private List<ByteBuffer>	buffers = new ArrayList<>();
		
		private AESemaphore sem  = new AESemaphore( "" );
		
		private boolean			closed;
		private IOException		error;
		
		private
		WSInputStream(
			int		_read_timeout )
		{
			read_timeout	= _read_timeout;
		}
		
		private void
		initialise(
			Session		_session )
		{
			session 	= _session;
		}
		
		private void
		write(
			ByteBuffer		buffer )
		{
			synchronized( this ){
				
				buffers.add( buffer );
				
				sem.release();
			}
		}
		
    	@Override
    	public int 
    	read() 
    		throws IOException 
    	{
    		while( true ){
    			
    			if ( !sem.reserve( read_timeout )){
    				
    				error( new IOException( "Read timeout" ));
    			}
    			
    			synchronized( this ){
    				
    				if ( buffers.isEmpty()){
    					
    					if ( closed ){
    						
    						if ( error == null ){
    						
    							return( -1 );
    							
    						}else{
    							
    							throw( error );
    						}
    					}
    				}else{
    					
    					ByteBuffer buffer = buffers.get(0);
    					
    					byte b = buffer.get();
    					
    					if ( !buffer.hasRemaining()){
    						
    						buffers.remove(0);
    					}
    					
    					if ( buffers.size() > 0 ){
    						
    						sem.release();
    					}
    					
    					return( b&0x00ff );
    				}
    			}
    		}
    		
    	}
    	
    	@Override
    	public int 
    	read(
    		byte[]		buf,
    		int			offset,
    		int			len )
    		
    		throws IOException 
    	{
    		int	remaining	= len;
    		
    		while( true ){
    			
    			if ( !sem.reserve( read_timeout )){
    				
    				error( new IOException( "Read timeout" ));
    			}
    			
    			synchronized( this ){
    				
    				if ( buffers.isEmpty()){
    					
    					if ( closed ){
    						
    						if ( error == null ){
    						
    							return( -1 );
    							
    						}else{
    							
    							throw( error );
    						}
    					}
    				}else{
    					
    					ByteBuffer buffer = buffers.get(0);
    					
    					int	buffer_rem = buffer.remaining();
    					
    					int to_read = Math.min( remaining, buffer_rem );
    					
    					buffer.get( buf, offset, to_read );
    					
    					remaining -= to_read;
    					
    					if ( !buffer.hasRemaining()){
    						
    						buffers.remove(0);
    					}
    					
    					if ( buffers.size() > 0 ){
    						
    						sem.release();
    					}

    					if ( remaining > 0 && buffers.size() > 0 ){
    						
    						offset += to_read;
    						
    					}else{
    					
    						return( len - remaining );
    					}
    				}
    			}
    		}
    		
    	}
    	
    	public void
    	close()
    	{
    		try{
	    		synchronized( this ){
	    			
	    			closed = true;
	    			
	    			sem.releaseForever();
	    		}
	    	}finally{
	    			
	    		_close();
	    	}
    	}
    	
    	private void
    	error(
    		Throwable		e )
    	{
    		try{
	    		synchronized( this ){
	    			
	    			closed = true;
	    			
	    			if ( e instanceof IOException ){
	    				
	    				error = (IOException)e;
	    				
	    			}else{
	    			
	    				error = new IOException( e );
	    			}
	    			
	    			sem.releaseForever();
	    		}
	    	}finally{
    			
	    		_close();
	    	}
    	}
    	
		private void
		_close()
		{
			try{
				session.close();
				
			}catch( Throwable e ){
				
			}
		}
	}
	
	private class
	WSOutputStream
		extends OutputStream
	{
		private Session				session;
		
		private
		WSOutputStream(
			Session				_session )
		{
			session		= _session;
		}
		
		@Override
		public void 
		write(int b) 
			throws IOException 
		{
			boolean ok = false;
			
			try{
				session.getBasicRemote().sendBinary( ByteBuffer.wrap( new byte[]{(byte)b} ));
				
				ok = true;
			
			}finally{
				
				if ( !ok ){
					_close();
				}
			}
		}
		
		@Override
		public void 
		write(byte[] b, int off, int len) 
				
			throws IOException 
		{
			boolean ok = false;
			
			try{
				session.getBasicRemote().sendBinary( ByteBuffer.wrap( b, off, len ));
		
				ok = true;
			
			}finally{
				
				if ( !ok ){
					_close();
				}
			}
		}
		
		@Override
		public void 
		write(byte[] b) 
			throws IOException 
		{
			boolean ok = false;
			
			try{
				session.getBasicRemote().sendBinary( ByteBuffer.wrap( b ));
				
				ok = true;
				
			}finally{
				
				if ( !ok ){
					_close();
				}
			}
		}
		
		@Override
		public void 
		flush() 
			throws IOException 
		{
		}
		
		@Override
		public void 
		close() 
			throws IOException 
		{
			_close();
		}
		
		private void
		_close()
		{
			try{
				session.close();
				
			}catch( Throwable e ){
				
			}
		}
	}
}
