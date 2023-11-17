/*
 * Created on Dec 18, 2015
 * Created by Paul Gardner
 * 
 * Copyright 2015 Azureus Software, Inc.  All rights reserved.
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



import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.biglybt.core.internat.MessageText;
import com.biglybt.core.torrent.TOTorrent;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.core.util.UrlUtils;
import com.biglybt.pif.*;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.ipc.IPCException;
import com.biglybt.pif.ipc.IPCInterface;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.InfoParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.LabelParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.menus.MenuItem;
import com.biglybt.pif.ui.menus.MenuItemFillListener;
import com.biglybt.pif.ui.menus.MenuItemListener;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.ui.tables.TableContextMenuItem;
import com.biglybt.pif.ui.tables.TableManager;
import com.biglybt.pif.ui.tables.TableRow;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import org.parg.azureus.plugins.webtorrent.GenericWSServer.ServerWrapper;



public class 
WebTorrentPlugin 
	implements Plugin
{
	private static final long instance_id = RandomUtils.nextSecureAbsoluteLong();
		
		// http://olegh.ftp.sh/public-stun.txt

	private final String[] ICE_URLS_DEFAULT = {
			"stun:stun.l.google.com:19302",
			"stun:global.stun.twilio.com:3478",
			
			"stun:stun1.l.google.com:19302",
			"stun:stun2.l.google.com:19302",
			"stun:stun3.l.google.com:19302",
			"stun:stun4.l.google.com:19302",
	};

	private String[] ice_urls = ICE_URLS_DEFAULT;

	private static final String DEFAULT_EXTERNAL_TRACKERS	= "wss://tracker.openwebtorrent.com/";
	
	private PluginInterface	plugin_interface;
	
	private LoggerChannel 			log;
	private LocaleUtilities 		loc_utils;
	private BasicPluginConfigModel 	config_model;
	private BasicPluginViewModel	view_model;
	
	private LabelParameter 			status_label;
	private StringParameter 		ice_param;
	private BooleanParameter		browser_impl;
	private BooleanParameter		browser_no_sandbox;
	
	private BooleanParameter		tracker_enable;
	private StringParameter			tracker_external;
	private BooleanParameter		tracker_ssl;
	private IntParameter			tracker_port;
	private StringParameter			tracker_bind;
	private StringParameter			tracker_host;
	private InfoParameter			tracker_url;
	private InfoParameter			tracker_stats;

	private List<TableContextMenuItem>		menus = new ArrayList<>();
	
	private LocalWebServer	web_server;
	private TrackerProxy	tracker_proxy;
	private WebRTCProvider	webrtc_provider;
		
	private BrowserManager	browser_manager = new BrowserManager();
	
	private GenericWSClient	gws_client;
	private GenericWSServer	gws_server;
	
	private WebTorrentTracker	tracker;
	
	private Object			active_lock	= new Object();
	private boolean			active;
	private long			last_activation_attempt;
	
	private boolean			unloaded;
	private boolean			destroyed;
	
	private void
	updateICEUrls()
	{
		String value = ice_param.getValue().trim();
		
		if ( value.isEmpty()){
			
			for ( String url: ICE_URLS_DEFAULT ){
				
				value += (value.isEmpty()?"":"\n") + url;
			}
			
			ice_param.setValue( value );
		}
		
		List<String> urls = new ArrayList<>();
		
		String[] lines = value.split( "\\n" );
		
		for ( String line: lines ){
			
			line = line.trim();
			
			if ( !line.isEmpty()){
				
				urls.add( line );
			}
		}
		
		ice_urls = urls.toArray( new String[0] );
	}
	
	@Override
	public void 
	initialize(
		PluginInterface _plugin_interface )
		
		throws PluginException 
	{
		plugin_interface = _plugin_interface;
				
		loc_utils = plugin_interface.getUtilities().getLocaleUtilities();
		
		log	= plugin_interface.getLogger().getTimeStampedChannel( "WebTorrent");
		
		final UIManager	ui_manager = plugin_interface.getUIManager();

		view_model = ui_manager.createBasicPluginViewModel( loc_utils.getLocalisedMessageText( "azwebtorrent.name" ));

		view_model.getActivity().setVisible( false );
		view_model.getProgress().setVisible( false );
		
		log.addListener(
				new LoggerChannelListener()
				{
					public void
					messageLogged(
						int		type,
						String	content )
					{
						view_model.getLogArea().appendText( content + "\n" );
					}
					
					public void
					messageLogged(
						String		str,
						Throwable	error )
					{
						view_model.getLogArea().appendText( str + "\n" );
						view_model.getLogArea().appendText( error.toString() + "\n" );
					}
				});
		
		config_model = ui_manager.createBasicPluginConfigModel( "plugins", "azwebtorrent.name" );

		view_model.setConfigSectionID( "azwebtorrent.name" );

		
		
		config_model.addLabelParameter2( "azwebtorrent.info" );

		config_model.addLabelParameter2( "azwebtorrent.blank" );

		config_model.addHyperlinkParameter2( "azwebtorrent.link", loc_utils.getLocalisedMessageText( "azwebtorrent.link.url" ));
		
		config_model.addLabelParameter2( "azwebtorrent.blank" );

		status_label = config_model.addLabelParameter2( "azwebtorrent.status");

		ice_param = config_model.addStringParameter2( "azwebtorrent.ice_servers", "azwebtorrent.ice_servers", "" );
		
		updateICEUrls();

		ice_param.setMultiLine( 5 );
		ice_param.setGenerateIntermediateEvents( false );
		
		ice_param.addListener(
				new ParameterListener()
				{
					@Override
					public void
					parameterChanged(
						Parameter 	param )
					{
						updateICEUrls();
					}
				});
		
		browser_impl = config_model.addBooleanParameter2( "azwebtorrent.impl.browser", "azwebtorrent.impl.browser", false );

		LabelParameter browser_info = config_model.addLabelParameter2( "azwebtorrent.browser.info" );

		final ActionParameter browser_launch_param = config_model.addActionParameter2( "azwebtorrent.browser.launch", "azwebtorrent.browser.launch.button" );
		
		browser_launch_param.addListener(
			new ParameterListener()
			{
				public void 
				parameterChanged(
					Parameter param ) 
				{
					browser_launch_param.setEnabled( false );
					
					Runnable cb = new Runnable()
					{
						@Override
						public void run() 
						{
							browser_launch_param.setEnabled( true );
						}
					};
					
					if ( active ){
						
						launchBrowser( cb );
								
					}else{
						
						activate( cb );
					}
				}
			});
		
		browser_no_sandbox = config_model.addBooleanParameter2( "azwebtorrent.browser.no.sandbox", "azwebtorrent.browser.no.sandbox", false );
		
		browser_impl.addEnabledOnSelection( browser_info, browser_launch_param, browser_no_sandbox );
		
			// tracker
		
		tracker_enable 		= config_model.addBooleanParameter2( "azwebtorrent.tracker.enable", "azwebtorrent.tracker.enable", false );
		tracker_external 	= config_model.addStringParameter2( "azwebtorrent.tracker.external", "azwebtorrent.tracker.external", DEFAULT_EXTERNAL_TRACKERS );
		tracker_ssl 	= config_model.addBooleanParameter2( "azwebtorrent.tracker.ssl", "azwebtorrent.tracker.ssl", false );
		tracker_port 	= config_model.addIntParameter2( "azwebtorrent.tracker.port", "azwebtorrent.tracker.port", 8000, 1, 65535 );
		tracker_bind 	= config_model.addStringParameter2( "azwebtorrent.tracker.bindip", "azwebtorrent.tracker.bindip", "" );
		tracker_host 	= config_model.addStringParameter2( "azwebtorrent.tracker.public.address", "azwebtorrent.tracker.public.address", "127.0.0.1" );
		tracker_url		= config_model.addInfoParameter2( "azwebtorrent.tracker.url", "" );
		tracker_stats	= config_model.addInfoParameter2( "azwebtorrent.tracker.status", "" );
			
		Parameter[] tracker_params = { 
				tracker_enable, tracker_external, tracker_ssl, tracker_port, tracker_bind,
				tracker_host, tracker_url, tracker_stats
		};
		
		ParameterListener	tracker_listener = new
			ParameterListener() {
				
				@Override
				public void parameterChanged(Parameter param) {
					setupTracker();
				}
			};
						
		for ( Parameter p : tracker_params ){
			
			p.setGenerateIntermediateEvents( false );

			if ( p == tracker_external ){
				
				tracker_enable.addDisabledOnSelection( p );

			}else{
				
				p.addListener( tracker_listener );
				
				if ( p != tracker_enable ){
				
					tracker_enable.addEnabledOnSelection( p );
				}
			}
		}
		
		config_model.createGroup( "azwebtorrent.tracker", tracker_params );
		
		/*
		final ActionParameter test_param = config_model.addActionParameter2( "azwebtorrent.test", "azwebtorrent.test" );
		
		test_param.addListener(
			new ParameterListener()
			{
				public void 
				parameterChanged(
					Parameter param ) 
				{
					runTest();
				}
			});
		*/
		
		MenuItemFillListener	menu_fill_listener = 
				new MenuItemFillListener()
				{
					public void
					menuWillBeShown(
						MenuItem	menu,
						Object		_target )
					{
						List<Download>	downloads = new ArrayList<>();
						
						if ( _target instanceof TableRow ){
							
							Object obj = ((TableRow)_target).getDataSource();
		
							if ( obj instanceof Download ){
								
								downloads.add((Download)obj);
							}
						}else{
							
							TableRow[] rows = (TableRow[])_target;
						     
							for ( TableRow row: rows ){
							
								Object obj = row.getDataSource();
								
								if ( obj instanceof Download ){
									
									downloads.add((Download)obj);
								}
							}
						}
						
						boolean	found = false;
						
						Set<String>	trackers = getTrackersForHosting();
							
						if ( trackers.size() > 0 ){
							
							for ( Download download: downloads ){
								
								Torrent torrent = download.getTorrent();
								
								if ( torrent != null ){
									
									TOTorrent to_torrent = PluginCoreUtils.unwrap( torrent );
									
									if ( !TorrentUtils.isReallyPrivate( to_torrent )){
										
										List<List<String>> existing_urls = TorrentUtils.announceGroupsToList( to_torrent );
										
										Set<String> temp = new HashSet<String>();
										
										for ( List<String> l: existing_urls ){
											
											temp.addAll(l);
										}
										
										if ( !temp.containsAll( trackers )){
											
											found = true;
											
											break;
										}
									}
								}
							}
						}
						
						menu.setEnabled( found );
					}
				};
			
			
			MenuItemListener	menu_listener = 
					new MenuItemListener()
					{
						public void
						selected(
							MenuItem		_menu,
							Object			_target )
						{
							List<Download>	downloads = new ArrayList<>();
							
							if ( _target instanceof TableRow ){
								
								Object obj = ((TableRow)_target).getDataSource();
			
								if ( obj instanceof Download ){
									
									downloads.add((Download)obj);
								}
							}else{
								
								TableRow[] rows = (TableRow[])_target;
							     
								for ( TableRow row: rows ){
								
									Object obj = row.getDataSource();
									
									if ( obj instanceof Download ){
										
										downloads.add((Download)obj);
									}
								}
							}
														
							Set<String>	trackers = getTrackersForHosting();
							
							for ( Download download: downloads ){
								
								Torrent torrent = download.getTorrent();
								
								if ( torrent != null ){
									
									TOTorrent to_torrent = PluginCoreUtils.unwrap( torrent );
									
									if ( !TorrentUtils.isReallyPrivate( to_torrent )){
										
										List<List<String>> existing_urls = TorrentUtils.announceGroupsToList( to_torrent );
												
										Set<String> trackers_copy = new HashSet<>( trackers );
	
										for ( List<String> l: existing_urls ){
											
											trackers_copy.removeAll( l );
										}
										
										if ( trackers_copy.size() > 0 ){
											
											List<List<String>> updated_urls = new ArrayList<List<String>>( existing_urls );
											
											for ( String str: trackers_copy ){
												
												List<String> l = new ArrayList<String>();
												
												l.add( str );
												
												updated_urls.add( l );
											}
											
											TorrentUtils.listToAnnounceGroups( updated_urls, to_torrent );
										}
									}
								}
							}
						}
					};
					
		String[] tables = {
				TableManager.TABLE_MYTORRENTS_COMPLETE,	
				TableManager.TABLE_MYTORRENTS_INCOMPLETE,	
				TableManager.TABLE_MYTORRENTS_COMPLETE_BIG,	
				TableManager.TABLE_MYTORRENTS_INCOMPLETE_BIG,	
				TableManager.TABLE_MYTORRENTS_ALL_BIG,
		};
			
		for ( String table: tables ){
			
			TableContextMenuItem menu_item 	= 
				plugin_interface.getUIManager().getTableManager().addContextMenuItem(
					table, 
					"azwebtorrent.menu.addtracker");

			menu_item.addFillListener( menu_fill_listener );
			
			menu_item.addListener( menu_listener );
			
			menus.add( menu_item );
		}
		
		plugin_interface.addListener(
			new PluginAdapter()
			{
				public void 
				closedownInitiated() 
				{
					synchronized( active_lock ){
						
						destroyed = true;
					}
					
					browser_manager.killBrowsers();
					
					deactivate();
				}
			});
		
		gws_client = new GenericWSClient();
		gws_server = new GenericWSServer();
		
		setupTracker();
	}
	
	public boolean
	useBrowserImplementation()
	{
		return( browser_impl.getValue());
	}
	
	public String[]
	getICEUrls()
	{
		return( ice_urls );
	}
	
	protected boolean
	getNoSandbox()
	{
		return( browser_no_sandbox.getValue());
	}
	
	private Set<String>
	getTrackersForHosting()
	{
		Set<String>	trackers = new HashSet<>();
		
		if ( tracker != null ){
			
			trackers.add(tracker.getURL());
			
		}else{
			
			String[] bits = tracker_external.getValue().replace( ';', ',' ).split( "," );
			
			for (String bit: bits ){
				
				bit = bit.trim();
				
				if ( bit.length() > 0 ){
					
					try{
						trackers.add( new URL( bit ).toExternalForm());
						
					}catch( Throwable e ){
						
						Debug.out( e );
					}
				}
			}
		}
		
		return( trackers );
	}
	
	private void
	setupTracker()
	{
		synchronized( this ){
						
			WebTorrentTracker	existing_tracker = tracker;

			if ( tracker_enable.getValue()){
								
				boolean	ssl		= tracker_ssl.getValue();
				int		port	= tracker_port.getValue();
				String	bind_ip	= tracker_bind.getValue().trim();
				String 	host	= tracker_host.getValue().trim();
				
				if ( existing_tracker != null ){
					
					if ( 	existing_tracker.isSSL() == ssl &&
							existing_tracker.getPort() == port &&
							existing_tracker.getBindIP().equals( bind_ip ) &&
							existing_tracker.getHost().equals( host )){
						
						return;
					}
					
					existing_tracker.destroy();
				}
				
				tracker	= new WebTorrentTracker( this, gws_server, ssl, bind_ip, port, host );

				tracker_url.setValue( tracker.getURL());
				
				try{
					tracker.start();
					
				}catch( Throwable e ){
					
					Debug.out( e );
					
					tracker.destroy();
					
					tracker = null;
				}
			}else{
				
				tracker_url.setValue( "" );
				
				if ( existing_tracker != null ){
					
					existing_tracker.destroy();
					
					tracker = null;
				}
			}
		}
	}
	
	public void
	updateTrackerStatus(
		int		sessions,
		int		torrents )
	{
		if ( tracker_stats != null ){
			
			tracker_stats.setValue( 
				loc_utils.getLocalisedMessageText(
					"azwebtorrent.tracker.status.detail",
					new String[]{
						String.valueOf( sessions ),
						String.valueOf( torrents )
					}));
		}
	}
	
	private boolean
	activate(
		Runnable	callback )
	{
		boolean	async = false;
		
		try{
			synchronized( active_lock ){
				
				if ( active ){
					
					return( true );
				}
				
				if ( destroyed ){
					
					return( false );
				}
				
				long	now = SystemTime.getMonotonousTime();
				
				if ( last_activation_attempt != 0 && now - last_activation_attempt < 30*1000 ){
					
					return( false );
				}
				
				last_activation_attempt = now;
				
				log( "Activating" );
				
				try{
					webrtc_provider = 
						WebRTCProviderManager.getProxy( 
							this, 
							instance_id,
							new WebRTCProvider.Callback() {
								
								@Override
								public void 
								requestNewBrowser() 
								{
									launchBrowser( null );
								}
							});
		
					tracker_proxy = 
						new TrackerProxy(
							this,
							new TrackerProxy.Listener()
							{
						    	@Override
						    	public void 
						    	getOffer(
						    		byte[] 							hash, 
						    		long 							timeout,
						    		WebRTCProvider.OfferListener	offer_listener) 
						    	{
						    		webrtc_provider.getOffer( hash, timeout, offer_listener );
						    	}
						    	
						    	@Override
						    	public void
						    	gotAnswer(
						    		byte[]		hash,
						    		String		offer_id,
						    		String		sdp )
						    		
						    		throws Exception
						    	{
						    		webrtc_provider.gotAnswer( offer_id, sdp );
						    	}
						    	
						    	@Override
						    	public void
						    	gotOffer(
						    		byte[]							hash,
						    		String							offer_id,
						    		String							sdp,
						    		WebRTCProvider.AnswerListener 	listener )
						    		
						    		throws Exception
						    	{
						    		webrtc_provider.gotOffer( hash, offer_id, sdp, listener );
						    	}
							});
					
					web_server = new LocalWebServer( ice_urls, instance_id, webrtc_provider.getPort(), tracker_proxy );
						
					status_label.setLabelText( loc_utils.getLocalisedMessageText( "azwebtorrent.status.ok" ));
	
					if ( useBrowserImplementation()){

						launchBrowser( callback );
					}
				
					async = true;
					
					active = true;
					
					return( true );
					
				}catch( Throwable e ){
					
					status_label.setLabelText( loc_utils.getLocalisedMessageText( "azwebtorrent.status.fail", new String[]{ Debug.getNestedExceptionMessage(e) }));
	
					active = false;
					
					log( "Activation failed", e );
					
					deactivate();
					
					return( false );
				}
			}
		}finally{
			
			if ( !async && callback != null ){
				
				callback.run();
			}
		}
	}
	
	private void
	launchBrowser(
		Runnable		callback )
	{
		String	url = "http://127.0.0.1:" + web_server.getPort() + "/index.html?id=" + instance_id;
	
		browser_manager.launchBrowser( this, url, callback );
	}
	
	private void
	deactivate()
	{
		synchronized( active_lock ){
			
			active = false;
			
			if ( web_server != null ){
				
				try{
					
					web_server.destroy();
					
				}catch( Throwable f ){
					
				}
				
				web_server = null;
			}
			
			if ( tracker_proxy != null ){
				
				try{
					
					tracker_proxy.destroy();
					
				}catch( Throwable f ){
					
				}
				
				tracker_proxy = null;
			}
			
			if ( webrtc_provider != null ){
				
				try{
					
					webrtc_provider.destroy();
					
				}catch( Throwable f ){
					
				}
				
				webrtc_provider = null;
			}
		}
	}
	
		// IPC starts
	
	
		/**
		 * used by the ws(s) protocol handler to get TRACKER SPECIFIC connection
		 * @param url
		 * @return
		 * @throws IPCException
		 */
	
	private void
	checkLoaded()
	
		throws IPCException
	{
		if ( unloaded ){
			
			throw( new IPCException( "IPC unavailable: plugin unloaded" ));
		}
	}
	
	public URL
	getProxyURL(
		URL		url )
		
		throws IPCException
	{
		checkLoaded();
		
		if ( activate( null )){
				
			try{
				return( new URL( "http://127.0.0.1:" + web_server.getPort() + "/?target=" + UrlUtils.encode( url.toExternalForm())));
				
			}catch( Throwable e ){
				
				throw( new IPCException( e ));
			}
		}else{
			
			throw( new IPCException( "Proxy unavailable" ));
		}
	}
	
	public void
	connect(
		URL					ws_url,
		Map<String,Object>	options,
		Map<String,Object>	result )
		
		throws IPCException
	{
		checkLoaded();
		
		try{
			gws_client.connect( ws_url, options, result );
			
		}catch( Throwable e ){
			
			throw( new IPCException( e ));
		}
	}
	
	public Object
	startServer(
		boolean				ssl,
		String				bind_ip,
		int					port,
		String				context,
		Map<String,Object>	options,
		IPCInterface		callback )
		
		throws IPCException
	{
		checkLoaded();
		
		try{
			return( gws_server.startServer( ssl, bind_ip, port, context, callback ));
			
		}catch( Throwable e ){
			
			throw( new IPCException( e ));
		}
	}
	
	public void
	stopServer(
		Object			server )
		
		throws IPCException
	{
		checkLoaded();
		
		gws_server.stopServer( (ServerWrapper)server );
	}
	
	public void
	sendMessage(
		Object		server,
		Object		session,
		ByteBuffer	message )
		
		throws IPCException
	{
		checkLoaded();
		
		try{
			gws_server.sendMessage( (ServerWrapper)server, (GenericWSServer)session, message );
			
		}catch( Throwable e ){
			
			throw( new IPCException( e ));
		}
	}
	
	public void
	sendMessage(
		Object		server,
		Object		session,
		String		message )
		
		throws IPCException
	{
		checkLoaded();
		
		try{
			gws_server.sendMessage( (ServerWrapper)server, (GenericWSServer)session, message );
			
		}catch( Throwable e ){
			
			throw( new IPCException( e ));
		}
	}
	
	public void
	closeSession(
		Object		server,
		Object		session )
		
		throws IPCException
	{
		checkLoaded();
		
		gws_server.closeSession( (ServerWrapper)server, (GenericWSServer)session );
	}
	
		// IPC ends
	
	private void
	runTest()
	{
		if ( false ){
			try{
				Map<String,Object>	options = new HashMap<>();
				Map<String,Object>	result = new HashMap<>();
				
				connect( 
					new URL( "ws://localhost:8025/websockets/vuze" ),
					options,
					result );
				
				OutputStream os = (OutputStream)result.get( "output_stream" );
				
				InputStream is = (InputStream)result.get( "input_stream" );
				
				os.write( "hello world".getBytes());
				
				os.flush();
				
				while( true ){
					
					byte[] buffer = new byte[1024];
					
					int len = is.read( buffer );
					
					if ( len == -1 ){
						
						break;
					}
					
					System.out.println( new String( buffer, 0, len ));
				}
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}else{
			
			try{
				startServer( true, "127.0.0.1", 7891, "/websockets", null, plugin_interface.getIPC());
				
			}catch( Throwable e ){
				
				Debug.out( e  );
			}
		}
	}
	
	public void
	sessionAdded(
		Object		server,
		URI			uri,
		Object		session )
	{
		System.out.println( "sessionAdded: " + uri );
	}
	
	public void
	sessionRemoved(
		Object		server,
		Object		session )
	{
		System.out.println( "sessionRemoved" );
	}
	
	public void
	messageReceived(
		Object		server,
		Object		session,
		ByteBuffer	message )
	{
		System.out.println( "messageReceived" );
		
		try{
			sendMessage( server, session, ByteBuffer.wrap( "derp".getBytes() ));
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
		// end of test
	
	/*
	public void
	unload()
	{
		// not unloadable with native impl and classloading issues
		 * 
		unloaded	= true;
		
		if ( config_model != null ){
			
			config_model.destroy();
			
			config_model = null;
		}
		
		if ( view_model != null ){
			
			view_model.destroy();
			
			view_model = null;
		}
		
		for (TableContextMenuItem menu_item: menus ){
			
			menu_item.remove();
		}
		
		menus.clear();
		
		deactivate();
		
		browser_manager.killBrowsers();
			
		if ( tracker != null ){
		
			tracker.destroy();
		
			tracker= null;
		}
		
		gws_server.unload();
	}
	*/
	
	public void
	setStatusError(
		Throwable	e )
	{
		String init_error = Debug.getNestedExceptionMessage( e );
		
		status_label.setLabelText( loc_utils.getLocalisedMessageText( "azwebtorrent.status.fail", new String[]{ init_error }) );
		
		log( "Plugin failed", e );	
		
		Debug.out( e );
	}
	
	public PluginInterface
	getPluginInterface()
	{
		return( plugin_interface );
	}
	
	public void
	log(
		String		str )
	{
		log.log( str );
	}
	
	public void
	log(
		String		str,
		Throwable	e )
	{
		log.log( str, e );
	}
	
	public static String
	encodeForJSON(
		byte[]	bytes )
	{
		String str = "";

		for ( byte b: bytes ){

			char c = (char)b;
			
			if ( c <= 255 && ( Character.isLetterOrDigit(c) || " {}:.=-".indexOf( c ) != -1)){

				str += c;
				
			}else{
				int	code = b&0xff;

				String s = Integer.toHexString( code );

				while( s.length() < 4 ){

					s = "0" + s;
				}

				str += "\\u" + s;
			}
		}

		return( str );
	}

	public static String
    encodeForJSON(
    	String str_in )
    {
       	String str = "";
    	
    	for (char c: str_in.toCharArray()){
    		
			if ( c <= 255 && c != '\\' && ( Character.isLetterOrDigit((char)c) || " {}:.=-".indexOf( c ) != -1)){
    		
    			str += c;
    			
    		}else{
    			
    			int	code = c&0xff;
    			
    			String s = Integer.toHexString( code );
    			
    			while( s.length() < 4 ){
    				
    				s = "0" + s;
    			}
    			
    			str += "\\u" + s;
    		}
    	}
    	
    	return( str );
    }
    
	public static String
	decodeForJSON(
		String		text )
	{
		try{
				// decode escaped unicode chars
			
			Pattern p = Pattern.compile("(?i)\\\\u([\\dabcdef]{4})");
	
			Matcher m = p.matcher( text );
	
			boolean result = m.find();
	
			if ( result ){
	
				StringBuffer sb = new StringBuffer();
	
		    	while( result ){
		    		
		    		 String str = m.group(1);
		    		 
		    		 int unicode = Integer.parseInt( str, 16 );
		    		 
		    		 m.appendReplacement(sb, Matcher.quoteReplacement( String.valueOf((char)unicode)));
		    		 
		    		 result = m.find(); 
		    	 }
	
				m.appendTail(sb);
	
				text = sb.toString();
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
		
		return( text );
	}
}
