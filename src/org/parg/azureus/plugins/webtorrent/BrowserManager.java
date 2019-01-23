/*
 * Created on Jan 15, 2016
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.FileUtil;
import com.biglybt.core.util.SystemTime;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;

import com.biglybt.core.util.GeneralUtils;

public class 
BrowserManager 
{
	/* Windows (Chromium)
	 * Removed <nnn>/
	 *    	ui tester
	 * OSX - 64 bit only (Chromium)
	 * Removed 
	 *		nothing  
	 * Linux
	 * Removed
	 *     cron
	 *     default_apps
	 *     PepperFlash 
	 */
	
	private static final String BROWSER_SIZE = "600,625";
	
	private static final String WIN_APP_NAME	= "chrome.exe";
	private static final String OSX_APP_NAME	= "Chromium.app";
	private static final String LINUX_APP_NAME	= "chrome";
	
	private Object			browser_lock = new Object();
	private Process			browser_process;

	private Set<BrowserInstance>		browser_instances = new HashSet<BrowserInstance>();

	
	private File
	checkBrowserInstall(
		WebTorrentPlugin		plugin )
	
		throws PluginException
	{
		try{
			PluginInterface	plugin_interface = plugin.getPluginInterface();
			
			File plugin_install_dir = new File( plugin_interface.getPluginDirectoryName());
				
			File plugin_data_dir	= plugin_interface.getPluginconfig().getPluginUserFile( "test" ).getParentFile();
			
			if ( !plugin_data_dir.exists()){
				
				plugin_data_dir.mkdirs();
			}
			
			deleteOldStuff( plugin_install_dir );
			deleteOldStuff( plugin_data_dir );
			
			File[]	install_files = plugin_install_dir.listFiles();
			
			List<File>	old_zip_files = new ArrayList<File>();
			
			String 	highest_version_zip			= "0";
			File	highest_version_zip_file	= null;
			
			for ( File file: install_files ){
				
				String name = file.getName();
				
				if ( file.isFile() && name.startsWith( "browser-" ) && name.endsWith( ".zip" )){
					
					String version = name.substring( name.lastIndexOf( "-" ) + 1, name.length() - 4 );
					
					if ( Constants.compareVersions( version, highest_version_zip ) > 0 ){
						
						highest_version_zip = version;
						
						if ( highest_version_zip_file != null ){
							
							old_zip_files.add( highest_version_zip_file );
						}
						
						highest_version_zip_file = file;
					}
				}
			}
			
			File[]	data_files = plugin_data_dir.listFiles();
			
			String 	highest_version_data		= "0";
			File	highest_version_data_file 	= null;
			
			for ( File file: data_files ){
				
				String name = file.getName();
				
				if ( file.isDirectory() && name.startsWith( "browser_" )){
					
					String version = name.substring( 8 );
					
					if ( Constants.compareVersions( version, highest_version_data ) > 0 ){
						
						highest_version_data = version;
						
						highest_version_data_file = file;
					}
				}
			}
						
			if ( Constants.compareVersions( highest_version_zip, highest_version_data ) > 0 ){
				
				File temp_data = new File( plugin_data_dir, "tmp_" + highest_version_zip );
				
				if ( temp_data.exists()){
					
					if ( !FileUtil.recursiveDeleteNoCheck( temp_data )){
						
						throw( new Exception( "Failed to remove tmp directory: " + temp_data ));
					}
				}
				
				if ( Constants.isOSX ){
				
					temp_data.mkdirs();
					
					File temp_zip = new File( temp_data, highest_version_zip_file.getName());
					
					FileUtil.copyFile( highest_version_zip_file, temp_zip );
					
					List<String> args = new ArrayList<>();
					
					args.add( "open" );
					args.add( "-W" );
					args.add( temp_zip.getAbsolutePath());
					
					ProcessBuilder pb = GeneralUtils.createProcessBuilder( temp_data, args.toArray(new String[args.size()]), null );

					pb.start().waitFor();
					
				}else{
					
					ZipInputStream zis = null;
					
					try{
						zis = new ZipInputStream( new BufferedInputStream( new FileInputStream( highest_version_zip_file ) ));
								
						byte[] buffer = new byte[64*1024];
						
						while( true ){
							
							ZipEntry	entry = zis.getNextEntry();
								
							if ( entry == null ){
								
								break;
							}
						
							String	name = entry.getName();
						
							if ( name.endsWith( "/" )){
								
								continue;
							}
							
							if ( File.separatorChar != '/' ){
								
								name = name.replace( '/', File.separatorChar );
							}
							
							File target_out = new File( temp_data, name );
							
							File parent_folder = target_out.getParentFile();
							
							if ( !parent_folder.exists()){
								
								parent_folder.mkdirs();
							}
							
							OutputStream	entry_os = null;
	
							try{
								entry_os = new FileOutputStream( target_out );
								
								while( true ){
									
									int	len = zis.read( buffer );
									
									if ( len <= 0 ){
										
										break;
									}
																												
									entry_os.write( buffer, 0, len );
								}
							}finally{
								
								if ( entry_os != null ){
									
									try{
										entry_os.close();
										
									}catch( Throwable e ){
										
										Debug.out( e );
									}
								}
							}
						}
					}finally{
						
						if ( zis != null ){
							
							try{
								zis.close();
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
					}
				}
				
				File target_data = new File( plugin_data_dir, "browser_" + highest_version_zip );
				
				if ( target_data.exists()){
					
					throw( new Exception( "Target already exists: " + target_data ));
				}
				
				if ( !temp_data.renameTo( target_data )){
					
					throw( new Exception( "Failed to rename " + temp_data + " to " + target_data ));
				}
				
				for ( File old: old_zip_files ){
					
					old.delete();
				}
								
				if ( Constants.isOSX || Constants.isLinux ){
					
					String chmod = findCommand( "chmod" );
					
					if ( chmod == null ){
						
						throw( new Exception( "Failed to find 'chmod' command" ));
					}
					
					Runtime.getRuntime().exec(
						new String[]{
							chmod,
							"-R",
							"+x",
							target_data.getAbsolutePath()
						});
				}
				
				return( target_data );

			}else{
				
				File existing_data = new File( plugin_data_dir, "browser_" + highest_version_data );

				if ( highest_version_data.equals( "0" ) || !existing_data.exists()){
					
					throw( new Exception( "No browser version installed" ));
				}
								
				return( existing_data );
			}
					
		}catch( Throwable e ){
			
			plugin.setStatusError( e );
			
			throw( new PluginException( "Browser setup failed: " + Debug.getNestedExceptionMessage( e )));
		}
	}
		
	private void
	deleteOldStuff(
		File		dir )
	{
		File[] files = dir.listFiles();
		
		if ( files == null || files.length == 0 ){
			
			return;
		}
		
		Map<String,List<Object[]>>	map = new HashMap<String,List<Object[]>>();
		
		for ( File f: files ){
			
			String name = f.getName();
			
			int	pos = name.lastIndexOf( '_' );
			
			if ( pos == -1 ){
				
				continue;
			}
			
			String root		= name.substring( 0, pos );
			String ver_str 	= name.substring( pos+1 );
			
			if ( ver_str.endsWith( ".jar" ) || ver_str.endsWith( ".zip" )){
				
				root += ver_str.substring(  ver_str.length() - 4 );
				
				ver_str = ver_str.substring( 0, ver_str.length() - 4 );
			}
			
			for ( char c: ver_str.toCharArray()){
				
				if ( c != '.' && !Character.isDigit( c )){
					
					ver_str = null;
					
					break;
				}
			}
			
			if ( ver_str != null && ver_str.length() > 0 ){
				
				List<Object[]> entry = map.get( root );
				
				if ( entry == null ){
					
					entry = new ArrayList<Object[]>();
					
					map.put( root, entry );
				}
				
				entry.add( new Object[]{ ver_str, f });
			}
		}
		
		for ( Map.Entry<String,List<Object[]>> entry: map.entrySet()){
			
			String 			root 	= entry.getKey();
			List<Object[]>	list	= entry.getValue();
			
			Collections.sort(
				list,
				new Comparator<Object[]>()
				{
					public int 
					compare(
						Object[] e1, 
						Object[] e2) 
					{
						String ver1 = (String)e1[0];
						String ver2 = (String)e2[0];
						
						return( Constants.compareVersions( ver1, ver2 ));
					}
				});
			
			/*
			System.out.println( root );
			
			for ( Object[] o: list ){
				
				System.out.println( "    " + o[0] + " - " + o[1] );
			}
			*/
			
			int	ver_to_delete = list.size() - 3;
							
			for ( int i=0;i<ver_to_delete;i++ ){
				
				File f = (File)list.get(i)[1];
				
				delete( f );
			}
		}
	}
	
	private void
	delete(
		File		f )
	{
		if ( f.isDirectory()){
						
			File[] files = f.listFiles();
			
			if ( files != null ){
				
				for ( File x: files ){
					
					delete( x );
				}
			}
		}
		
		f.delete();
	}
		

	protected void
	launchBrowser(
		final WebTorrentPlugin		plugin,
		final String				url,
		final Runnable				callback )
	{
		new AEThread2( "")
		{
			public void
			run()
			{
				synchronized( browser_lock ){
										
					if ( browser_process != null ){
						
						try{
							browser_process.destroy();
							
						}catch( Throwable e ){
						}
					}
					

					try{						
						File browser_dir = checkBrowserInstall( plugin );
						
						File plugin_dir = plugin.getPluginInterface().getPluginconfig().getPluginUserFile( "test" ).getParentFile();
						
						File data_dir 		= new File( plugin_dir, "data" );
		
						data_dir.mkdirs();
						browser_dir.mkdirs();
							
						List<String>	args = new ArrayList<>();
						
						String[] fixed_args = {
							"--window-size=" + BROWSER_SIZE,
							"--incognito",
							"--no-default-browser-check",
							"--no-first-run",
							"--js-flags=\"--expose-gc\"",
							"--user-data-dir=" + (Constants.isOSX?data_dir.getAbsolutePath().replaceAll(" ", "\\ "):("\"" + data_dir.getAbsolutePath() + "\""))
						};
							
						
						if ( Constants.isWindows ){
							
							args.add( WIN_APP_NAME );
							
							for ( String fa: fixed_args ){
								args.add( fa );
							}
							
							args.add( url );
							
						}else if ( Constants.isOSX ){
							
							args.add( "open" );
							args.add( "-a" );
							args.add( new File( browser_dir, OSX_APP_NAME ).getAbsolutePath());
							args.add( url );
							args.add( "--args" );
							
							for ( String fa: fixed_args ){
								args.add( fa );
							}
						}else{
							
							args.add( "./" + LINUX_APP_NAME );
							
							for ( String fa: fixed_args ){
								args.add( fa );
							}
							
							args.add( url );
						}
						
						ProcessBuilder pb = GeneralUtils.createProcessBuilder( browser_dir, args.toArray(new String[args.size()]), null );
	
						if ( Constants.isOSX ){
							char slash = File.separatorChar;
							
							pb.environment().put(
								"DYLD_LIBRARY_PATH",
								browser_dir.getAbsolutePath() + slash + OSX_APP_NAME + slash + "Contents" + slash + "MacOS" );
						}
						
						new BrowserInstance( pb );
												
					}catch( Throwable e ){
						
						plugin.log( "Failed to launch browser", e );
						
					}finally{
						
						if ( callback != null ){
							
							callback.run();
						}
					}
				}
			}
		}.start();
	}
	
	protected void
	killBrowsers()
	{
		final AESemaphore sem = new AESemaphore( "waiter" );
		
			// just in case something blocks here...
		
		new AEThread2( "killer")
		{
			public void
			run()
			{
				try{
					synchronized( browser_instances ){
						
						for ( BrowserInstance b: browser_instances ){
							
							b.destroy();
						}
						
						browser_instances.clear();
					}
				}finally{
					
					sem.release();
				}
			}
		}.start();
		
		sem.reserve( 2500 );
	}
	
	
	private Set<Integer>
	getBrowserProcesses()
	{
		if ( Constants.isWindows ){
			
			return( getWindowsProcesses( WIN_APP_NAME ));
			
		}else if(  Constants.isOSX ){
			
			return( getOSXProcesses( OSX_APP_NAME ));
			
		}else{
			
			return( getLinuxProcesses( LINUX_APP_NAME, null ));
		}
	}
	
	private Set<Integer>
	getWindowsProcesses(
		String	exe )
	{
		Set<Integer>	result = new HashSet<Integer>();
		
		try{
			
			Process p = Runtime.getRuntime().exec( new String[]{ "cmd", "/c", "tasklist" });
			
			try{
				LineNumberReader lnr = new LineNumberReader( new InputStreamReader( p.getInputStream(), "UTF-8" ));
				
				while( true ){
					
					String line = lnr.readLine();
					
					if ( line == null ){
						
						break;
					}
					
					if ( line.startsWith( exe )){
						
						String[] bits = line.split( "\\s+" );
						
						if ( bits.length >= 2 ){
							
							String	exe_name 	= bits[0].trim();
							
							if ( exe_name.equals( exe )){
								
								try{
									int		pid 		= Integer.parseInt( bits[1].trim());
								
									result.add( pid );
									
								}catch( Throwable e ){
									
								}
							}
						}
					}
				}					
			}finally{
				
				p.destroy();
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
		
		return( result );
	}
	
	private Set<Integer>
	getOSXProcesses(
		String	cmd  )
	{
		Set<Integer>	result = new HashSet<Integer>();
		
		try{
			
			Process p = Runtime.getRuntime().exec( new String[]{ findCommand( "bash" ), "-c", "ps ax" });
			
			try{
				LineNumberReader lnr = new LineNumberReader( new InputStreamReader( p.getInputStream(), "UTF-8" ));
				
				while( true ){
					
					String line = lnr.readLine();
					
					if ( line == null ){
						
						break;
					}
					
					if ( line.contains( cmd )){
						
						String[] bits = line.split( "\\s+" );
						
						for ( int i=0;i<bits.length;i++ ){
							
							String bit = bits[i].trim();
							
							if ( bit.length() == 0 ){
								
								continue;
							}
							
							try{
								int		pid 		= Integer.parseInt( bit );
																
								result.add( pid );
								
							}catch( Throwable e ){
								
							}
							
							break;
						}
					}
				}					
			}finally{
				
				p.destroy();
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
		
		return( result );
	}
	
	private Set<Integer>
	getLinuxProcesses(
		String	cmd,
		String	exclude_str )
	{
		Set<Integer>	result = new HashSet<Integer>();
		
		try{
			
			Process p = Runtime.getRuntime().exec( new String[]{ findCommand( "bash" ), "-c", "ps ax" });
			
			try{
				LineNumberReader lnr = new LineNumberReader( new InputStreamReader( p.getInputStream(), "UTF-8" ));
				
				while( true ){
					
					String line = lnr.readLine();
					
					if ( line == null ){
						
						break;
					}
					
					if ( line.contains( cmd )){
						
						if ( exclude_str != null && line.contains( exclude_str )){
							
							continue;
						}
						
						String[] bits = line.split( "\\s+" );
						
						for ( int i=0;i<bits.length;i++ ){
							
							String bit = bits[i].trim();
							
							if ( bit.length() == 0 ){
								
								continue;
							}
							
							try{
								int		pid 		= Integer.parseInt( bit );
																
								result.add( pid );
								
							}catch( Throwable e ){
								
							}
							
							break;
						}
					}
				}					
			}finally{
				
				p.destroy();
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
		
		return( result );
	}
	
	private String
	findCommand(
		String	name )
	{
		final String[]  locations = { "/bin", "/usr/bin" };

		for ( String s: locations ){

			File f = new File( s, name );

			if ( f.exists() && f.canRead()){

				return( f.getAbsolutePath());
			}
		}

		return( name );
	}
	
	private void
	logDebug(
		String		str )
	{
		//System.out.println( str );
	}
	
	private class
	BrowserInstance
	{
		private Process		process;
		private int			process_id	= -1;
		
		private List<AEThread2>	threads = new ArrayList<AEThread2>();
		
		private volatile boolean	destroyed;
		
		private
		BrowserInstance(
			ProcessBuilder		pb )
		
			throws IOException
		{		
				
				// process.destroy doesn't work on Windows :( - rumour is it sends a SIG_TERM which is ignored
				
			Set<Integer>	pre_procs = getBrowserProcesses();
			
			process = pb.start();	
				
			long	now = SystemTime.getMonotonousTime();
			
			while( SystemTime.getMonotonousTime() - now < 5*1000 ){
				
				Set<Integer>	post_procs = getBrowserProcesses();
				
				for ( Integer s: pre_procs ){
						
					post_procs.remove( s );
				}
					
				if ( post_procs.size() > 0 ){
						
					if ( post_procs.size() == 1 ){
					
						process_id = post_procs.iterator().next();
					}
					
					break;
				}	
				
				try{
					Thread.sleep(1000);
					
				}catch( Throwable e ){
					
					break;
				}
			}
			
			try{
				int	num_proc;
				
				synchronized( browser_instances ){
					
					browser_instances.add( this );
					
					num_proc = browser_instances.size();
				}

				if ( num_proc == 1 ){
					
					logDebug( "Main browser process started" );
					
				}else{
					
					logDebug( "Sub-process started" );
				}
				
				AEThread2 thread =
					new AEThread2( "WSBrowser:proc_read_out" )
					{
						public void
						run()
						{
							try{
								LineNumberReader lnr = new LineNumberReader( new InputStreamReader( process.getInputStream()));
								
								while( true ){
								
									String line = lnr.readLine();
									
									if ( line == null ){
										
										break;
									}
									
									logDebug( "> " + line );
														}
							}catch( Throwable e ){
								
							}
						}
					};
					
				threads.add( thread );
				
				thread.start();
				
				thread =
					new AEThread2( "WSBrowser:proc_read_err" )
					{
						public void
						run()
						{
							try{
								LineNumberReader lnr = new LineNumberReader( new InputStreamReader( process.getErrorStream()));
								
								while( true ){
								
									String line = lnr.readLine();
									
									if ( line == null ){
										
										break;
									}
																	
									logDebug( "* " + line );
								}
							}catch( Throwable e ){
								
							}
						}
					};
					
				threads.add( thread );
					
				thread.start();

				thread =
					new AEThread2( "WSBrowser:proc_wait" )
					{
						public void
						run()
						{
							try{
								process.waitFor();
								
							}catch( Throwable e ){
								
							}finally{
								
								int	num_proc;
								
								synchronized( browser_instances ){
									
									browser_instances.remove( BrowserInstance.this );
									
									num_proc = browser_instances.size();
								}
								
								if ( num_proc == 0 ){
								
									logDebug( "Main browser process exited" );
									
								}else{
									
									logDebug( "Sub-process exited" );
								}
							}
						}
					};
					
				threads.add( thread );
					
				thread.start();	
				
			}catch( Throwable e ){
				
				synchronized( browser_instances ){
					
					browser_instances.remove( this );
				}
				
				logDebug( "Process setup failed: " + Debug.getNestedExceptionMessage( e));
				
				destroy();
			}
		}
		
		private void
		destroy()
		{
			destroyed = true;
			
			try{	
				for ( AEThread2 thread: threads ){
					
					thread.interrupt();
				}
				
				process.getOutputStream().close();
				
				process.destroy();
				
				/* pointless as chrome is a separate process from the 'main' one on Windows
				if ( Constants.isWindows && process_id >= 0 ){
					
					logDebug( "Killing process " + process_id );
					
					Process p = Runtime.getRuntime().exec( new String[]{ "cmd", "/c", "taskkill", "/f", "/pid", String.valueOf( process_id ) });

					p.waitFor();
				}
				*/
				
			}catch( Throwable e ){
				
			}
		}
	}
}
