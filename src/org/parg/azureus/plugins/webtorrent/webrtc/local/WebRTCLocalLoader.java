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

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;

import org.parg.azureus.plugins.webtorrent.WebRTCProvider;
import org.parg.azureus.plugins.webtorrent.WebTorrentPlugin;
import org.parg.azureus.plugins.webtorrent.WebRTCProvider.Callback;
import org.parg.azureus.plugins.webtorrent.webrtc.WebRTCPeerBridge;

import com.biglybt.pif.PluginInterface;

// import net.bytebuddy.agent.ByteBuddyAgent;

public class 
WebRTCLocalLoader
{
		/*
		 * Prior to version 0.6.0 of web-rtc it would crash when not loaded via the system class loader
		 * so we had to hack the system class loader to ensure this
		 */
	
	private static final boolean HAS_CLASSLOADER_BUG = false;
	
	public static WebRTCProvider
	load(
		WebTorrentPlugin		_plugin,
		WebRTCPeerBridge		_peer_bridge,
		long					_instance_id,
		final Callback			_callback )
	
		throws Exception
	{
		if ( HAS_CLASSLOADER_BUG ){	
			
			/*
			boolean	already_loaded = false;
			
			try{
				Class.forName( 
					"dev.onvoid.webrtc.PeerConnectionFactory", 
					true, 
					ClassLoader.getSystemClassLoader());
				
				already_loaded = true;
				
			}catch( Throwable e ){
			}
			
			if ( !already_loaded ){
				
				PluginInterface pi = _plugin.getPluginInterface();
				
				Instrumentation inst = ByteBuddyAgent.install();
				
				URL[] urls = ((URLClassLoader)pi.getPluginClassLoader()).getURLs();
						
				for ( URL url: urls ){
		
					try{
						File f = new File(url.toURI());
						
						if ( f.exists()){
							
							String name = f.getName();
						
							if ( name.startsWith( "webrtc-java" )){
		
								JarFile	jf = new JarFile( f );
				
								inst.appendToSystemClassLoaderSearch( jf );
							}
						}
					}catch( Throwable e ){
						
					}
				}
				
				Class.forName( 
						"dev.onvoid.webrtc.PeerConnectionFactory", 
						true, 
						ClassLoader.getSystemClassLoader());
			}
			*/
		}
		
		return( new WebRTCLocalImpl( _plugin, _peer_bridge, _instance_id, _callback ));
	}
}
