/*
 * Created on Jan 6, 2014
 * Created by Paul Gardner
 * 
 * Copyright 2014 Azureus Software, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */



package org.parg.azureus.plugins.networks.torbrowser;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.biglybt.core.util.GeneralUtils;
import com.biglybt.core.internat.MessageText;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.FileUtil;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.pif.PluginAdapter;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.ipc.IPCException;
import com.biglybt.pif.ipc.IPCInterface;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.LabelParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.ui.swt.shells.MessageBoxShell;


public class 
TorBrowserPlugin
	implements UnloadablePlugin
{
	public static final String HOME_PAGE = "https://check.torproject.org/";

	private PluginInterface				plugin_interface;
	private BasicPluginConfigModel 		config_model;
	private BasicPluginViewModel		view_model;
	private LoggerChannel				log;
	
	private IPCInterface		tor_ipc;
	
	private String				init_error;
	private File				browser_dir;
	
	private boolean	debug_log;
	
	private AESemaphore					init_complete_sem = new AESemaphore( "tbp_init" );
	
	private Set<BrowserInstance>		browser_instances = new HashSet<BrowserInstance>();
	
	private TimerEventPeriodic			browser_timer;
	
	private AsyncDispatcher		launch_dispatcher = new AsyncDispatcher( "Tor:launcher" );
	
	private static final int LAUNCH_TIMEOUT_INIT 	= 30*1000;
	private static final int LAUNCH_TIMEOUT_NEXT	= 1000;
	
	private int	launch_timeout	= LAUNCH_TIMEOUT_INIT;
	
	private String	last_check_log = "";
	
	@Override
	public void
	initialize(
		PluginInterface		pi )
	
		throws PluginException 
	{
		plugin_interface = pi;

		setUnloadable( true );
		
		final LocaleUtilities loc_utils = plugin_interface.getUtilities().getLocaleUtilities();

		log	= plugin_interface.getLogger().getTimeStampedChannel( "TorBrowser");
		
		final UIManager	ui_manager = plugin_interface.getUIManager();

		view_model = ui_manager.createBasicPluginViewModel( loc_utils.getLocalisedMessageText( "aztorbrowserplugin.name" ));

		view_model.getActivity().setVisible( false );
		view_model.getProgress().setVisible( false );
		
		log.addListener(
				new LoggerChannelListener()
				{
					@Override
					public void
					messageLogged(
						int		type,
						String	content )
					{
						view_model.getLogArea().appendText( content + "\n" );
					}
					
					@Override
					public void
					messageLogged(
						String		str,
						Throwable	error )
					{
						view_model.getLogArea().appendText( str + "\n" );
						view_model.getLogArea().appendText( error.toString() + "\n" );
					}
				});

		config_model = ui_manager.createBasicPluginConfigModel( "plugins", "aztorbrowserplugin.name" );

		config_model.addLabelParameter2( "aztorbrowserplugin.info1" );
		config_model.addLabelParameter2( "aztorbrowserplugin.info2" );

		config_model.addLabelParameter2( "aztorbrowserplugin.blank" );

		config_model.addHyperlinkParameter2( "aztorbrowserplugin.link", loc_utils.getLocalisedMessageText( "aztorbrowserplugin.link.url" ));
		
		config_model.addLabelParameter2( "aztorbrowserplugin.blank" );

		final LabelParameter status_label = config_model.addLabelParameter2( "aztorbrowserplugin.status");
		
		LabelParameter sep3 = config_model.addLabelParameter2( "aztorbrowserplugin.blank" );
		
		view_model.setConfigSectionID( "aztorbrowserplugin.name" );

		final ActionParameter launch_param = config_model.addActionParameter2( "aztorbrowserplugin.launch", "aztorbrowserplugin.launch.button" );
		
		launch_param.addListener(
			new ParameterListener()
			{
				@Override
				public void
				parameterChanged(
					Parameter param ) 
				{
					launch_param.setEnabled( false );
					
					try{
						launchBrowser( 
							HOME_PAGE,
							true,
							new Runnable()
							{
								@Override
								public void
								run()
								{
									launch_param.setEnabled( true );
								}
							});
						
					}catch( Throwable e ){
						
						launch_param.setEnabled( true );
						
						ui_manager.showTextMessage(
								"aztorbrowserplugin.launch.fail.msg",
								null,
								"Browser launch failed: " + Debug.getNestedExceptionMessage(e));
					}
				}
			});
		
		final BooleanParameter debug_log_param 	= config_model.addBooleanParameter2( "debug_log", "aztorbrowserplugin.debug_log", false );

		debug_log = debug_log_param.getValue();
		
		debug_log_param.addListener(
			new ParameterListener()
			{
				@Override
				public void
				parameterChanged(
					Parameter param ) 
				{
					debug_log = debug_log_param.getValue();
				}
			});
		
		config_model.createGroup( 
			"aztorbrowserplugin.browser.group",
			new Parameter[]{
					status_label, sep3, launch_param, debug_log_param,
			});
		
		try{
			File plugin_install_dir = new File( pi.getPluginDirectoryName());
			
			File plugin_data_dir	= pi.getPluginconfig().getPluginUserFile( "test" ).getParentFile();
			
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
				
					// migrate any existing profile data
				
				if ( highest_version_data_file != null ){
					
					char slash = File.separatorChar;

					// Version 4.0 - Data moved from /Data to [Browser|TorBrowser.app]/TorBrowser/Data
					// Windows and Linux use 'Browser'
					
					String	top_level_folder = Constants.isOSX?"TorBrowser.app":"Browser";
					
					File	old_profile = new File( highest_version_data_file, "Data" );
					
					if ( !old_profile.exists()){
						
						old_profile = new File( highest_version_data_file, top_level_folder + slash + "TorBrowser" + slash + "Data" );
					}
					
					File	new_profile = new File( temp_data, "Data" );
					
					if ( !new_profile.exists()){
						
						new_profile = new File( temp_data, top_level_folder + slash + "TorBrowser" + slash + "Data" );
					}
					
					copyProfile( old_profile, new_profile );		
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
				
				browser_dir = target_data;

			}else{
				
				File existing_data = new File( plugin_data_dir, "browser_" + highest_version_data );

				if ( highest_version_data.equals( "0" ) || !existing_data.exists()){
					
					throw( new Exception( "No browser version installed" ));
				}
								
				browser_dir = existing_data;
			}
			
			plugin_interface.addListener(
				new PluginAdapter()
				{
					@Override
					public void
					initializationComplete()
					{
						try{
							checkConfig();
							
							status_label.setLabelText( loc_utils.getLocalisedMessageText( "aztorbrowserplugin.status.ok" ));
							
							log( "Initialization complete" );
							
						}catch( Throwable e ){
							
							init_error = Debug.getNestedExceptionMessage( e );
							
							status_label.setLabelText( loc_utils.getLocalisedMessageText( "aztorbrowserplugin.status.fail", new String[]{ init_error }) );

							Debug.out( e );
							
							log( "Initialization failed: " + init_error );
							
						}finally{
							
							init_complete_sem.releaseForever();
						}
					}
					
					@Override
					public void
					closedownInitiated() 
					{
						killBrowsers();
					}
				});
				
		}catch( Throwable e ){
			
			init_error = Debug.getNestedExceptionMessage( e );
			
			status_label.setLabelText( loc_utils.getLocalisedMessageText( "aztorbrowserplugin.status.fail", new String[]{ init_error }) );
			
			log( "Initialization failed: " + init_error );
			
			throw( new PluginException( "Initialisation failed: " + Debug.getNestedExceptionMessage( e )));
		}
	}
	
	private void
	copyProfile(
		File	from_dir,
		File	to_dir )
	
		throws Exception
	{
		if ( !from_dir.isDirectory()){
			
			return;
		}
		
		File[] from_files = from_dir.listFiles();
		
		for ( File from_file: from_files ){
			
			File to_file = new File( to_dir, from_file.getName());
			
			if ( from_file.isDirectory()){
				
				if ( !to_file.exists()){
					
					if ( !to_file.mkdirs()){
						
						throw( new Exception( "Failed to create dir: " + to_file ));
					}
				}
				
				copyProfile( from_file, to_file );
				
			}else{
				/*
				if ( to_file.exists()){
					
					if ( !to_file.delete()){
						
						throw( new Exception( "Failed to delete file: " + to_file ));
					}
				}
				*/
				
				// logic changed 3.5.3 to only preserve files that don't exist in the new profile as we need
				// to update extensions etc
				
				if ( !to_file.exists()){
					
					if ( !FileUtil.copyFile( from_file, to_file )){
						
						// changed to not be a terminal error as seen this occur for whatever reason :(
						
						String name = from_file.getName().toLowerCase( Locale.US );
						
						if ( !name.equals( ".ds_store" )){
						
							Debug.out( "Failed to copy file: " + from_file + " -> " + to_file );
						}
					}
				}
			}
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
					@Override
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
	
	private IPCInterface
	getTorIPC()
		
		throws Exception
	{
		IPCInterface result = tor_ipc;
		
		if ( result == null ){
			
			PluginInterface tor_pi = plugin_interface.getPluginManager().getPluginInterfaceByID( "aznettor", true );
			
			if ( tor_pi != null ){
				
				result = tor_ipc = tor_pi.getIPC();
				
			}else{
				
				throw( new Exception( "Tor Helper Plugin not installed" ));
			}
		}
		
		return( result );
	}
	
	private int	config_last_port = 0;
	
	private void
	checkConfig()
	
		throws Exception
	{
		IPCInterface ipc = getTorIPC();
		
		if ( !ipc.canInvoke( "getConfig", new Object[0] )){
			
			throw( new Exception( "Tor Helper Plugin needs updating" ));
		}
		
		int	socks_port;

		try{
			Map<String,Object>	config = (Map<String,Object>)ipc.invoke( "getConfig", new Object[0] );
		
			socks_port = (Integer)config.get( "socks_port" );
			
		}catch( Throwable e ){
			
			throw( new Exception( "Tor Helper Plugin communication failure", e ));

		}
		
		if ( config_last_port == socks_port ){
			
			// can't see any harm in doing this everytime - deals with the case whereby someone has an old browser hanging around,
			// starts Vuze and then kills the old browser (which causes the old browser to most likely re-write its config and trash
			// over the port if different
			//return;
		}
		
		config_last_port = socks_port;
		
		log( "Tor socks port is " + socks_port );
		
		Map<String,Object> user_pref = new HashMap<String, Object>();
		 
		user_pref.put("browser.startup.homepage", HOME_PAGE );
		user_pref.put("network.proxy.no_proxies_on", "127.0.0.1");
		user_pref.put("network.proxy.socks_port", socks_port );
		
		user_pref.put("extensions.torbutton.lastUpdateCheck", "1999999999.000" );	// we handle this
		user_pref.put("extensions.torbutton.updateNeeded", false );	// we handle this

		Set<String>	user_pref_opt = new HashSet<String>();
		
		user_pref_opt.add( "browser.startup.homepage" );
		user_pref_opt.add( "network.proxy.no_proxies_on" );
		
		Map<String,Object> ext_pref = new HashMap<String, Object>();

		ext_pref.put("extensions.torbutton.fresh_install", true );
		ext_pref.put("extensions.torbutton.tor_enabled", true);
		ext_pref.put("extensions.torbutton.proxies_applied", false );
		ext_pref.put("extensions.torbutton.settings_applied", false );
		ext_pref.put("extensions.torbutton.socks_host", "127.0.0.1");
		ext_pref.put("extensions.torbutton.socks_port", socks_port );
		ext_pref.put("extensions.torbutton.custom.socks_host", "127.0.0.1");
		ext_pref.put("extensions.torbutton.custom.socks_port", socks_port );
		ext_pref.put("extensions.torbutton.settings_method", "custom");

		
		File	root = browser_dir;
		
		if ( root == null ){
			
			throw( new Exception( "Browser not installed" ));
		}
		
		char slash = File.separatorChar;

		// Version 4.0 - Data moved from /Data to [Browser|TorBrowser.app]/TorBrowser/Data
		// Windows and Linux use 'Browser'
		
		String	top_level_folder = Constants.isOSX?"TorBrowser.app":"Browser";

		File	profile_dir = new File( root, top_level_folder + slash + "TorBrowser" + slash + "Data" + slash + "Browser" + slash + "profile.default" );
		
		profile_dir.mkdirs();
		
		File	user_prefs_file = new File( profile_dir, "prefs.js" );
		
		fixPrefs( user_prefs_file, "user_pref", user_pref, user_pref_opt );
		
		File	ext_prefs_dir = new File( profile_dir, "preferences" );

		ext_prefs_dir.mkdirs();
		
		File	ext_prefs_file = new File( ext_prefs_dir, "extension-overrides.js" );
		
		fixPrefs( ext_prefs_file, "pref", ext_pref, new HashSet<String>() );
	}
	
	private void
	fixPrefs(
		File				file,
		String				pref_key,
		Map<String,Object>	prefs,
		Set<String>			optional_keys )
	
		throws Exception
	{
		List<String>	lines = new ArrayList<String>();
		
		boolean updated = false;
		
		Map<String,Object>	prefs_to_add = new HashMap<String, Object>( prefs );
		
		if ( file.exists()){
			
			LineNumberReader	lnr = null;
			
			try{
				lnr = new LineNumberReader( new InputStreamReader( new FileInputStream( file ), "UTF-8" ));
				
				while( true ){
					
					String 	line = lnr.readLine();
					
					if ( line == null ){
						
						break;
					}
					
					line = line.trim();
					
					boolean	handled = false;
					
					if ( line.startsWith( pref_key )){
						
						int	pos1 = line.indexOf( "\"" );
						int	pos2 = line.indexOf( "\"", pos1+1 );
						
						if ( pos2 > pos1 ){
							
							String key = line.substring( pos1+1, pos2 );
							
							Object	required_value = prefs_to_add.remove( key );
							
							if ( required_value != null ){
								
								pos1 = line.indexOf( ",", pos2 + 1 );
								pos2 = line.indexOf( ")", pos1+1 );
								
								if ( pos2 > pos1 ){
									
									String	current_str = line.substring( pos1+1, pos2 ).trim();
									
									String 	required_str;
									
									if ( required_value instanceof String ){
										
										required_str = "\"" + required_value + "\"";
									}else{
										
										required_str = String.valueOf( required_value );
									}
									
									if ( !current_str.equals( required_str )){
										
										lines.add( pref_key + "(\"" + key + "\", " + required_str + ");");
										
										updated	= true;
										handled = true;
									}
								}else{
									
									throw( new Exception( "Couldn't parse line: " + line ));
								}
							}
						}
					}
					
					if ( !handled ){
						
						lines.add( line );
					}
				}
			}finally{
				
				if ( lnr != null ){
					
					lnr.close();
				}
			}
		}
		
		for ( Map.Entry<String, Object> entry: prefs_to_add.entrySet()){
			
			String key = entry.getKey();
			
				// if all we are missing is optional keys then don't flag as updated (might be flagged
				// so due to other things of course)
				// we're dealing with the case that some settings are used-and-removed from the pref
				// config when firefox runs and we don't want to keep re-writing them
			
			if ( !optional_keys.contains( key )){
				
				updated = true;
			}
			
			Object val = entry.getValue();
			
			if ( val instanceof String ){
				
				val = "\"" + val + "\"";
			}
			
			lines.add( pref_key + "(\"" + key + "\", " + val + ");");
		}
		
		if ( updated ){
			
			logDebug( "Updating " + file );
			
			File temp_file 	= new File( file.getAbsolutePath() + ".tmp" );
			File bak_file 	= new File( file.getAbsolutePath() + ".bak" );
			
			temp_file.delete();
			
			try{
				PrintWriter writer = new PrintWriter( new OutputStreamWriter( new FileOutputStream( temp_file ), "UTF-8" ));
				
				try{
					for ( String line: lines ){
					
						writer.println( line );
					}
				}finally{
					
					writer.close();
				}
				
				bak_file.delete();
				
				if ( file.exists()){
					
					if ( !file.renameTo( bak_file )){
						
						throw( new Exception( "Rename of " + file + " to " + bak_file + " failed" ));
					}
				}
					
				if ( !temp_file.renameTo( file )){
						
					if ( bak_file.exists()){
						
						bak_file.renameTo( file );
					}
					
					throw( new Exception( "Rename of " + temp_file + " to " + file + " failed" ));
				}
			}catch( Throwable e ){
				
				temp_file.delete();
				
				throw( new Exception( "Failed to udpate " + file, e ));
			}	
		}
	}
	
	
	private void
	launchBrowser(
		final String		url,
		final boolean		new_window,
		final Runnable		run_when_done )
	
		throws Exception
	{	
		log( "Launch request for " + (url==null?"<default>":url) + ", new window=" + new_window );
	
		if ( !init_complete_sem.isReleasedForever()){
			
			log( "Waiting for initialisation to complete" );
			
			init_complete_sem.reserve(60*1000);
		}
		
		if ( init_error != null ){
			
			throw( new Exception( "Browser initialisation failed: " + init_error ));
		}

		final File	root = browser_dir;

		if ( root == null ){
						
			throw( new Exception( "Browser not installed" ));
		}
		
		launch_dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport() 
				{
					try{
						launchBrowserSupport( root, url, new_window, run_when_done );
						
					}catch( Throwable e ){
						
						log( "Launch failed: " + Debug.getNestedExceptionMessage( e ));
					}
				}
			});
	}
	
	private void
	launchBrowserSupport(
		File			root,
		String			url,
		boolean			new_window,
		Runnable		run_when_done ) 
	
		throws Exception
	{
		try{
			long	now = SystemTime.getMonotonousTime();
			
			while( true ){
				
				if ( checkTor()){
					
					launch_timeout	= LAUNCH_TIMEOUT_INIT;
					
					break;
				}
			
				if ( SystemTime.getMonotonousTime() - now > launch_timeout ){
				
					log( "Timeout waiting for Tor to start" );
					
					launch_timeout	= LAUNCH_TIMEOUT_NEXT;
					
					break;
				}
				
				try{
					Thread.sleep( 1000 );
					
				}catch( Throwable e ){
					
				}
			}
			
			boolean	new_launch;
			
			synchronized( browser_instances ){
			
				new_launch = browser_instances.size() == 0;
			}

			if ( new_launch ){
				
				if ( !checkFirefox()){
					
					throw( new Exception( "Launch cancelled" ));
				}
				
				checkConfig();
			}
			
			List<String>	cmd_list = new ArrayList<String>();
		
			String	browser_root = root.getAbsolutePath();
			
			String slash = File.separator;
			
			// Version 4.0 - Data moved from /Data to [Browser|TorBrowser.app]/TorBrowser/Data
			// Windows and Linux use 'Browser'
			
			String	top_level_folder = Constants.isOSX?"TorBrowser.app":"Browser";
			
			String PROFILE_DIR = browser_root + slash + top_level_folder + slash + "TorBrowser" + slash + "Data" + slash + "Browser" + slash + "profile.default";
						
			if ( Constants.isWindows ){
		
				cmd_list.add( browser_root + slash + "Browser" + slash + "firefox.exe" );
				
				cmd_list.add( "-profile" );
				
				cmd_list.add( PROFILE_DIR + slash );
				
				cmd_list.add( "-allow-remote" );
							
				if ( url != null ){
					
					if ( new_window ){
						
						cmd_list.add( "-new-window"  );
							
					}else{
							
						cmd_list.add( "-new-tab"  );
					}
								
					cmd_list.add( "\"" + url + "\"" );
				}
			}else if ( Constants.isOSX ){
								
				if ( new_launch ){
										
					cmd_list.add( browser_root + slash + "TorBrowser.app" + slash + "Contents" + slash + "MacOS" + slash + "firefox" );
					
					cmd_list.add( "-profile" );
					
					cmd_list.add( PROFILE_DIR );
					
					cmd_list.add( "-allow-remote" );
					
					if ( url != null ){
													
						if ( new_window ){
						
							cmd_list.add( "-new-window"  );
							
						}else{
							
							cmd_list.add( "-new-tab"  );
						}
							
						cmd_list.add( url );
					}					
				}else{
										
					cmd_list.add( "open" );
					
					cmd_list.add( "-a" );
					
					cmd_list.add( browser_root + slash + "TorBrowser.app" );
					
					if ( url != null ){
							
						cmd_list.add( url );
					}
											
					cmd_list.add( "--args" );
					
					cmd_list.add( "-profile" );
					
					cmd_list.add( PROFILE_DIR );
					
					cmd_list.add( "-allow-remote" );
					
					if ( url != null ){
						
						if ( new_window ){
							
							cmd_list.add( "-new-window"  );
							
						}else{
							
							cmd_list.add( "-new-tab"  );
						}
					}
				}								
			}else if ( Constants.isLinux ){
				
				cmd_list.add( browser_root + slash + "Browser" + slash + "start-tor-browser" );
				
				cmd_list.add( "-profile" );
				
				cmd_list.add( PROFILE_DIR + slash );
				
				cmd_list.add( "-allow-remote" );
				
				if ( url != null ){
											
					if ( new_window ){
					
						cmd_list.add( "-new-window"  );
						
					}else{
						
						cmd_list.add( "-new-tab"  );
					}
						
					cmd_list.add( url );
				}
				
			}else{
				
				throw( new Exception( "Unsupported OS" ));
			}
			
			ProcessBuilder pb = GeneralUtils.createProcessBuilder( root, cmd_list.toArray(new String[cmd_list.size()]), null );
			
			if ( Constants.isOSX ){
				
				pb.environment().put(
					"DYLD_LIBRARY_PATH",
					browser_root + slash + "TorBrowser.app" + slash + "Contents" + slash + "MacOS" );
			}
					
			BrowserInstance browser = new BrowserInstance( pb );	
			
			if ( Constants.isOSX && new_launch ){
			
				int	proc_id = browser.getProcessID();
				
				if ( proc_id > 0 ){
					
					String NL = "\n";
					
					String script =
						"tell application \"System Events\"" + NL +
						"  set theprocs to every process whose unix id is " + proc_id + NL +
						"  repeat with proc in theprocs" + NL +
						"     set the frontmost of proc to true" + NL +
						"  end repeat" + NL +
						"end tell" + NL;
					
					Runtime.getRuntime().exec( new String[]{ findCommand( "osascript" ), "-e", script });
				}
			}
		}finally{
			
			if ( run_when_done != null ){
				
				run_when_done.run();
			}
		}
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
	setUnloadable(
		boolean	b )
	{
		PluginInterface pi = plugin_interface;
		
		if ( pi != null ){
			
			pi.getPluginProperties().put( "plugin.unload.disabled", String.valueOf( !b ));
		}
	}
	
	@Override
	public void
	unload() 
			
		throws PluginException 
	{
		synchronized( browser_instances ){
			
			if ( browser_instances.size() > 0 ){
				
				throw( new PluginException( "Unload prevented as browsers are active" ));
			}
		}
		
		browser_dir 		= null;
		init_error			= null;
		plugin_interface	= null;
		
		if ( config_model != null ){
			
			config_model.destroy();
			
			config_model = null;
		}
		
		if ( view_model != null ){
			
			view_model.destroy();
			
			view_model = null;
		}
		
			// should be null op due to above test, but leave here for completeness
		
		killBrowsers();
	}
	
	private void
	killBrowsers()
	{
		final AESemaphore sem = new AESemaphore( "waiter" );
		
			// just in case something blocks here...
		
		new AEThread2( "killer")
		{
			@Override
			public void
			run()
			{
				try{
					synchronized( browser_instances ){
						
						for ( BrowserInstance b: browser_instances ){
							
							b.destroy();
						}
						
						browser_instances.clear();
						
						setUnloadable( true );
						
						if ( browser_timer != null ){
							
							browser_timer.cancel();
							
							browser_timer = null;
						}
					}
				}finally{
					
					sem.release();
				}
			}
		}.start();
		
		sem.reserve( 2500 );
	}
	
	private boolean
	checkTor()
	{
		try{
			IPCInterface ipc = getTorIPC();
			
			if ( !ipc.canInvoke( "requestActivation", new Object[0] )){
				
				return( false );
			}
			
			return( (Boolean)ipc.invoke( "requestActivation", new Object[0] ));
			
		}catch( Throwable e ){
			
			return( false );
		}
	}
	
	private void
	checkBrowsers()
	{
		int	num_active;
		
		synchronized( browser_instances ){
		
			num_active = browser_instances.size();
			
			if ( num_active == 0 ){
			
				if ( browser_timer != null ){
				
					browser_timer.cancel();
				
					browser_timer = null;
				}
			}
		}
		
		if ( num_active > 0 ){
		
			checkTor();
		}
		
		String str = "Actve browsers: " + num_active;
		
		if ( !last_check_log.equals( str )){
			
			log( str );
			
			last_check_log = str;
		}
	}
	
	private boolean
	checkFirefox()
	{
			// OSX doesn't suffer from the existing firefox + -allow-remote issue
		
		if (  Constants.isOSX ){
			
			return( true );
		}
		
		Set<Integer> pids = getFireFoxProcesses();
		
		if ( pids.size() == 0 ){
			
			return( true );
		}
				
		String title 	= MessageText.getString( "aztorbrowserplugin.firefox.found.title" );
		String text 	= MessageText.getString( "aztorbrowserplugin.firefox.found.text" );
		
		/*		
		UIFunctions uif = UIFunctionsManager.getUIFunctions();
		
		if ( uif == null ){
			
			return( true );
		}

		UIFunctionsUserPrompter prompter = uif.getUserPrompter(title, text, new String[] {
			MessageText.getString("Button.yes"),
			MessageText.getString("Button.no")
		}, 0);
		
		*/
	
		MessageBoxShell prompter = new MessageBoxShell(title, text, new String[] {
				MessageText.getString("Button.yes"),
				MessageText.getString("Button.no")
			}, 0 );
	
		
		String remember_id = "aznettorbrowser.firefox.found";
		
		prompter.setRemember( 
			remember_id, 
			false,
			MessageText.getString("MessageBoxWindow.nomoreprompting"));
	
		prompter.setRememberOnlyIfButton( 0 );
		
		prompter.setAutoCloseInMS(0);
		
		prompter.open( null );
		
		return( prompter.waitUntilClosed() == 0 );
	}
	
	private Set<Integer>
	getFireFoxProcesses()
	{
		if ( Constants.isWindows ){
			
			return( getWindowsProcesses( "firefox.exe" ));
			
		}else if(  Constants.isOSX ){
			
			return( getOSXProcesses( "Firefox.app" ));
			
		}else{
			
			return( getLinuxProcesses( "firefox", "no-remote" ));
		}
	}
	
	private Set<Integer>
	getTorBrowserProcesses()
	{
		if ( Constants.isWindows ){
			
			return( getWindowsProcesses( "firefox.exe" ));
			
		}else if(  Constants.isOSX ){
			
			return( getOSXProcesses( "TorBrowser.app" ));
			
		}else{
			
			return( getLinuxProcesses( "TorBrowser", null ));
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
			
			logDebug( "Failed to list tasks: " + Debug.getNestedExceptionMessage( e ));
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
			
			logDebug( "Failed to list processes: " + Debug.getNestedExceptionMessage( e ));
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
			
			logDebug( "Failed to list processes: " + Debug.getNestedExceptionMessage( e ));
		}
		
		return( result );
	}
	
		// IPC methods
	
	public void
	launchURL(
		URL			url )
	
		throws IPCException
	{
		launchURL( url, false );
	}
	
	public void
	launchURL(
		URL			url,
		boolean		new_window )
	
		throws IPCException
	{
		launchURL( url, false, null );
	}
	
	public void
	launchURL(
		URL			url,
		boolean		new_window,
		Runnable	run_when_done )
	
		throws IPCException
	{
		try{
			launchBrowser( url==null?null:url.toExternalForm(), new_window, run_when_done );
			
		}catch( Throwable e ){
			
			throw( new IPCException( "Launch url failed", e ));
		}
	}
	
	private void
	logDebug(
		String		str )
	{
		if ( debug_log ){
			
			log( str );
		}
	}
	
	private void
	log(
		String		str )
	{
		log.log( str );
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
				
			Set<Integer>	pre_procs = getTorBrowserProcesses();
			
			process = pb.start();	
				
			long	now = SystemTime.getMonotonousTime();
			
			while( SystemTime.getMonotonousTime() - now < 5*1000 ){
				
				Set<Integer>	post_procs = getTorBrowserProcesses();
				
				for ( Integer s: pre_procs ){
						
					post_procs.remove( s );
				}
					
				if ( post_procs.size() > 0 ){
						
					process_id = post_procs.iterator().next();
					
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
					
					setUnloadable( false );
					
					if ( browser_timer == null ){
						
						browser_timer = 
							SimpleTimer.addPeriodicEvent(
								"TBChecker",
								30*1000,
								new TimerEventPerformer()
								{	
									@Override
									public void
									perform(
										TimerEvent event) 
									{
										checkBrowsers();
									}
								});
					}
				}

				if ( num_proc == 1 ){
					
					logDebug( "Main browser process started" );
					
				}else{
					
					logDebug( "Sub-process started" );
				}
				
				if ( browser_dir == null ){
					
					throw( new Exception( "Unloaded" ));
				}
				
				AEThread2 thread =
					new AEThread2( "TorBrowser:proc_read_out" )
					{
						@Override
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
					new AEThread2( "TorBrowser:proc_read_err" )
					{
						@Override
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
					new AEThread2( "TorBrowser:proc_wait" )
					{
						@Override
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
									
									setUnloadable( num_proc == 0 );
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
					
					setUnloadable( browser_instances.size() == 0 );
				}
				
				logDebug( "Process setup failed: " + Debug.getNestedExceptionMessage( e));
				
				destroy();
			}
		}
		
		private int
		getProcessID()
		{
			return( process_id );
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
				
				if ( Constants.isWindows && process_id >= 0 ){
					
					logDebug( "Killing process " + process_id );
					
					Process p = Runtime.getRuntime().exec( new String[]{ "cmd", "/c", "taskkill", "/f", "/pid", String.valueOf( process_id ) });

					p.waitFor();
				}
			}catch( Throwable e ){
				
			}
		}
	}
}
