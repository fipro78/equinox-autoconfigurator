/*******************************************************************************
 * Copyright (c) 2003, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Dirk Fauth - Modification to support auto-start installed bundles
 *******************************************************************************/
package org.eclipse.equinox.autoconfigurator;

import java.io.File;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.service.debug.DebugOptions;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.util.tracker.ServiceTracker;

public class ConfigurationActivator implements BundleActivator {

	public static String PI_CONFIGURATOR = "org.eclipse.equinox.autoconfigurator"; //$NON-NLS-1$
	public static final String UPDATE_PREFIX = "update@"; //$NON-NLS-1$
	private static final String INITIAL_PREFIX = "initial@"; //$NON-NLS-1$
	public static final String PLUGINS = "plugins"; //$NON-NLS-1$
	
	public static boolean isWindows = System.getProperty("os.name").startsWith("Win"); //$NON-NLS-1$ //$NON-NLS-2$
	
	private ServiceTracker<Location, Location> instanceLocation;

	// debug options
	public static String OPTION_DEBUG = PI_CONFIGURATOR + "/debug"; //$NON-NLS-1$
	// debug values
	public static boolean DEBUG = false;
	
	private FrameworkLog log;

	private BundleContext context;

	public void start(BundleContext ctx) throws Exception {
		context = ctx;
		loadOptions();
		acquireFrameworkLogService();

		debug("Starting configurator..."); //$NON-NLS-1$

		installBundles();
	}
	
	public void stop(BundleContext ctx) throws Exception {
		if (instanceLocation != null) {
			instanceLocation.close();
			instanceLocation = null;
		}		
	}

	public boolean installBundles() {
		debug("Installing bundles..."); //$NON-NLS-1$
		int startLevel = 4;
		String defaultStartLevel = context.getProperty("osgi.bundles.defaultStartLevel"); //$NON-NLS-1$
		if (defaultStartLevel != null) {
			try {
				startLevel = Integer.parseInt(defaultStartLevel);
			} catch (NumberFormatException e1) {
				startLevel = 4;
			}
		}
		if (startLevel < 1)
			startLevel = 4;

		try {
			// Get the list of cached bundles and compare with the ones to be installed.
			// Uninstall all the cached bundles that do not appear on the new list
			Bundle[] cachedBundles = context.getBundles();
			
			URL install = getInstallURL();
			File temp = new File(install.getFile()+"/"+PLUGINS);

			Set<String> plugins = new HashSet<String>();
			File[] fList = temp.listFiles();
	        for (File file : fList){
	            if (file.isFile() && file.toString().endsWith(".jar")) {
	                plugins.add(PLUGINS+File.separatorChar+file.getName());
	            }
	        }

	        // Java 7 version
//			Path installPath = temp.toPath();
//			Path pluginPath = Paths.get(temp.getAbsolutePath(), PLUGINS);
//			
//			Set<String> plugins = new HashSet<String>();
//			try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginPath, "*.{jar}")) {
//				for (Path entry: stream) {
//					plugins.add(installPath.relativize(entry).toString());
//				}
//			} catch (IOException x) {
//			    // IOException can never be thrown by the iteration.
//			    // In this snippet, it can // only be thrown by newDirectoryStream.
//			    log(x.getLocalizedMessage(), x);
//			}
			
			// starts the list of bundles to refresh with all currently unresolved bundles (see bug 50680)
			List<Bundle> toRefresh = getUnresolvedBundles();

			Bundle[] bundlesToUninstall = getBundlesToUninstall(cachedBundles, plugins);
			
			for (int i = 0; i < bundlesToUninstall.length; i++) {
				try {
					if (DEBUG)
						debug("Uninstalling " + bundlesToUninstall[i].getLocation()); //$NON-NLS-1$
					// include every bundle being uninstalled in the list of bundles to refresh (see bug 82393)					
					toRefresh.add(bundlesToUninstall[i]);
					bundlesToUninstall[i].uninstall();
				} catch (Exception e) {
					log(MessageFormat.format("Could not uninstall unused bundle {0}", bundlesToUninstall[i]));
				}
			}

			// Get the urls to install
			String[] bundlesToInstall = getBundlesToInstall(cachedBundles, plugins);
			
			List<Bundle> installedBundles = new ArrayList<Bundle>(bundlesToInstall.length);
			for (int i = 0; i < bundlesToInstall.length; i++) {
				try {
					if (DEBUG)
						debug("Installing " + bundlesToInstall[i]); //$NON-NLS-1$
					URL bundleURL = new URL("reference:file:" + bundlesToInstall[i]); //$NON-NLS-1$
					//Bundle target = context.installBundle(bundlesToInstall[i]);
					Bundle target = context.installBundle(UPDATE_PREFIX + bundlesToInstall[i], bundleURL.openStream());
					// any new bundle should be refreshed as well
					toRefresh.add(target);
					target.adapt(BundleStartLevel.class).setStartLevel(startLevel);
					installedBundles.add(target);
				} catch (Exception e) {
					if (!isAutomaticallyStartedBundle(bundlesToInstall[i]))
						log(MessageFormat.format("Could not install bundle {0}", bundlesToInstall[i]) + "   " + e.getMessage()); //$NON-NLS-1$
				}
			}
			
			removeInitialBundles(toRefresh, cachedBundles);
			refreshPackages(toRefresh);
			
			for (Bundle bundle : context.getBundles()) {
				if (bundle.getState() == Bundle.RESOLVED) {
					try {
						// use the START_ACTIVATION_POLICY option so this is not an eager activation.
						bundle.start(Bundle.START_ACTIVATION_POLICY);
					} catch (BundleException e) {
						if ((bundle.getState() & Bundle.RESOLVED) != 0)
							// only log errors if the bundle is resolved
							log(MessageFormat.format("Could not start bundle {0}", bundle.getLocation()) + "   " + e.getMessage()); //$NON-NLS-1$
					}
				}
			}
			
			return true;
		} catch (Exception e) {
			log(e.getLocalizedMessage(), e);
			return false;
		}
	}

	private void removeInitialBundles(List<Bundle> bundles, Bundle[] cachedBundles) {
		String[] initialSymbolicNames = getInitialSymbolicNames(cachedBundles);
		Iterator<Bundle> iter = bundles.iterator();
		while (iter.hasNext()) {
			Bundle bundle = (Bundle) iter.next();
			String symbolicName = bundle.getSymbolicName();
			for (int i = 0; i < initialSymbolicNames.length; i++) {
				if (initialSymbolicNames[i].equals(symbolicName)) {
					iter.remove();
					break;
				}
			}
		}
	}

	private String[] getInitialSymbolicNames(Bundle[] cachedBundles) {
		List<String> initial = new ArrayList<String>();
		for (int i = 0; i < cachedBundles.length; i++) {
			Bundle bundle = cachedBundles[i];
			if (bundle.getLocation().startsWith(INITIAL_PREFIX)) {
				String symbolicName = bundle.getSymbolicName();
				if (symbolicName != null)
					initial.add(symbolicName);
			}
		}
		return initial.toArray(new String[0]);
	}

	private List<Bundle> getUnresolvedBundles() {
		Bundle[] allBundles = context.getBundles();
		List<Bundle> unresolved = new ArrayList<Bundle>();
		for (int i = 0; i < allBundles.length; i++)
			if (allBundles[i].getState() == Bundle.INSTALLED)
				unresolved.add(allBundles[i]);
		return unresolved;
	}

	private String[] getBundlesToInstall(Bundle[] cachedBundles, Set<String> newPlugins) {
		// First, create a map of the cached bundles, for faster lookup
		Set<String> cachedBundlesSet = new HashSet<String>(cachedBundles.length);
		int offset = UPDATE_PREFIX.length();
		for (int i = 0; i < cachedBundles.length; i++) {
			if (cachedBundles[i].getBundleId() == 0)
				continue; // skip the system bundle
			if (PI_CONFIGURATOR.equals(cachedBundles[i].getSymbolicName()))
				continue; // skip ourself
			String bundleLocation = cachedBundles[i].getLocation();
			// Ignore bundles not installed by us
			if (!bundleLocation.startsWith(UPDATE_PREFIX))
				continue;

			bundleLocation = bundleLocation.substring(offset);
			cachedBundlesSet.add(bundleLocation);
			// On windows, we will be doing case insensitive search as well, so lower it now
			if (isWindows)
				cachedBundlesSet.add(bundleLocation.toLowerCase());
		}

		List<String> bundlesToInstall = new ArrayList<String>(newPlugins.size());
		for (String location : newPlugins) {
			// check if already installed
			if (cachedBundlesSet.contains(location))
				continue;
			if (isWindows && cachedBundlesSet.contains(location.toLowerCase()))
				continue;
			if (location.contains(PI_CONFIGURATOR))
				continue;

			bundlesToInstall.add(location);
		}
		return bundlesToInstall.toArray(new String[0]);
	}

	private Bundle[] getBundlesToUninstall(Bundle[] cachedBundles, Set<String> newPlugins) {
		// On windows, we will be doing case insensitive search as well, so lower it now
		for (String pluginLocation : newPlugins) {
			if (isWindows) {
				newPlugins.add(pluginLocation.toLowerCase());
			}
		}

		List<Bundle> bundlesToUninstall = new ArrayList<Bundle>();
		int offset = UPDATE_PREFIX.length();
		for (int i = 0; i < cachedBundles.length; i++) {
			if (cachedBundles[i].getBundleId() == 0)
				continue; // skip the system bundle
			if (PI_CONFIGURATOR.equals(cachedBundles[i].getSymbolicName()))
				continue; // skip ourself
			String cachedBundleLocation = cachedBundles[i].getLocation();
			// Only worry about bundles we installed
			if (!cachedBundleLocation.startsWith(UPDATE_PREFIX))
				continue;
			cachedBundleLocation = cachedBundleLocation.substring(offset);

			if (newPlugins.contains(cachedBundleLocation))
				continue;
			if (isWindows && newPlugins.contains(cachedBundleLocation.toLowerCase()))
				continue;

			bundlesToUninstall.add(cachedBundles[i]);
		}
		return bundlesToUninstall.toArray(new Bundle[0]);
	}


	/**
	 * Do FrameworkWiring.refreshBundles() in a synchronous way.  After installing
	 * all the requested bundles we need to do a refresh and want to ensure that 
	 * everything is done before returning.
	 * @param bundles
	 */
	private void refreshPackages(List<Bundle> bundles) {
		if (bundles == null || bundles.size() == 0)
			return;
		
		final boolean[] flag = new boolean[] {false};
		
		FrameworkWiring frameworkWiring = context.getBundle(0).adapt(FrameworkWiring.class);
		if (frameworkWiring != null) {
			frameworkWiring.refreshBundles(bundles, new FrameworkListener() {
				@Override
				public void frameworkEvent(FrameworkEvent event) {
					if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED)
						synchronized (flag) {
							flag[0] = true;
							flag.notifyAll();
						}
					}
				}
			);
		}
		
		synchronized (flag) {
			while (!flag[0]) {
				try {
					flag.wait();
				} catch (InterruptedException e) {
				}
			}
		}
	}

	private void loadOptions() {
		// all this is only to get the application args		
		DebugOptions service = null;
		ServiceReference<DebugOptions> reference = context.getServiceReference(DebugOptions.class);
		if (reference != null)
			service = context.getService(reference);
		if (service == null)
			return;
		try {
			DEBUG = service.getBooleanOption(OPTION_DEBUG, false);
		} finally {
			// we have what we want - release the service
			context.ungetService(reference);
		}
	}

	public boolean isAutomaticallyStartedBundle(String bundleURL) {
		if (bundleURL.indexOf("org.eclipse.osgi") != -1) //$NON-NLS-1$
			return true;
		
		String osgiBundles = context.getProperty("osgi.bundles"); //$NON-NLS-1$
		StringTokenizer st = new StringTokenizer(osgiBundles, ","); //$NON-NLS-1$
		while (st.hasMoreTokens()) {
			String token = st.nextToken().trim();
			int index = token.indexOf('@');
			if (index != -1)
				token = token.substring(0,index);
			if (token.startsWith("reference:file:")) { //$NON-NLS-1$
				File f = new File(token.substring(15));
				if (bundleURL.indexOf(f.getName()) != -1)
					return true;
			}
			if (bundleURL.indexOf(token) != -1)
				return true;
		}
		return false;
	}
	
	/**
	 * Return the install location.
	 * 
	 * @see Location
	 */
	public synchronized URL getInstallURL() {
		if (instanceLocation == null) {
			Filter filter = null;
			try {
				filter = context.createFilter(Location.INSTALL_FILTER);
			} catch (InvalidSyntaxException e) {
				// ignore this. It should never happen as we have tested the
				// above format.
			}
			instanceLocation = new ServiceTracker<Location, Location>(context, filter, null);
			instanceLocation.open();
		}

		Location location = (Location) instanceLocation.getService();

		// it is pretty much impossible for the install location to be null.  If it is, the
		// system is in a bad way so throw and exception and get the heck outta here.
		if (location == null)
			throw new IllegalStateException("The installation location must not be null"); //$NON-NLS-1$

		return  location.getURL();
	}

	public void debug(String s) {
		if (ConfigurationActivator.DEBUG)
			System.out.println("PlatformConfig: " + s); //$NON-NLS-1$
	}
	
	private void acquireFrameworkLogService() {
		ServiceReference<FrameworkLog> logServiceReference = context.getServiceReference(FrameworkLog.class);
		if (logServiceReference == null)
			return;
		log = context.getService(logServiceReference);
	}

	private void log(String message) {
		log(message, null);
	}
	
	private void log(String message, Throwable throwable) {
		log.log(new FrameworkLogEntry(PI_CONFIGURATOR, 4, 0, message, 0, throwable, null));
	}
}
