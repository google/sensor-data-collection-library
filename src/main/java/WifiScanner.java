/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Copyright 2011 Google Inc. All Rights Reserved.



import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.util.Pair;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Uses the Android WifiManager to scan for 802.11 access points using Android's
 * ScanResult API.  IMPORTANT NOTE ABOUT PRIVACY: The ScanResult objects
 * returned from these scans contain only SSID, MAC address, and signal strength
 * of the access point.  The device does not forge a data connection with the
 * access point, nor does it collect any payload from the network.  That means
 * that no web pages, usernames, or passwords will be sent to the logger.
 *
 * Listeners indicate how frequently they would like scans, up to potentially
 * continuous scanning. This implementation includes a kicker thread so that it
 * is robust against phones that occasionally fail to complete a requested scan.
 *
 * Note that the scanner will switch to continuous scanning mode if any
 * listener asks for any value less than the SCAN_TIMEOUT, currently 4 seconds.
 *
 * The WifiManager broadcasts about completed scans via the Android
 * BroadcastReceiver mechanism.
 */
public class WifiScanner extends BroadcastReceiver implements Runnable {
  /**
   * The maximum amount of time that this library will wait for a wifi scan to
   * complete before assuming that it's never going to finish. In milliseconds.
   * This is also the shortest amount of time that a Listener can request
   * periodic scanning. Any interval lower than this will put the scanner into
   * continuous mode.
   */
  public static final long SCAN_TIMEOUT = 4000L;

  /**
   * A listener interface with which callers can receive scans.
   */
  public static interface Listener {
    public void onScanResult(List<ScanResult> results);
  }

  // A singleton instance of this class. It is created when the first listener
  // is interested in running the WifiScanner, and it is shut down and nulled
  // away when the last listener un-subscribes.
  private static WifiScanner instance = null;
  
  // The listeners interested in scan results
  private final Set<Pair<Listener, Long>> listeners = new HashSet<Pair<Listener, Long>>();

  // The context to use for registering for broadcasts
  private final Context ctx;
  
  // The Wifi manager
  private final WifiManager manager;

  // True if the kicker thread should be running.
  private boolean isRunning = false;
  private Thread kickerThread;

  // The last system time, in milliseconds, that we started a scan and got a
  // result, respectively
  private long lastResultTime = 0L;

  // The smallest wait interval requested amongst all of the currently
  // subscribed listeners. If this is any value less than SCAN_TIMEOUT, then the
  // scanner will switch to continuous scanning mode.
  private long scanInterval = Long.MAX_VALUE;

  /**
   * Private constructor. Use the static listener subscription methods to get
   * messages about wifi scans.
   */
  private WifiScanner(Context ctx) {
    this.ctx = ctx;
    
    // get the manager and ask it to start scanning
    manager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
    manager.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, "WifiScanner");

    // also launch the kicker thread.
    isRunning = true;
    kickerThread = new Thread(this);
    kickerThread.setDaemon(true);
    kickerThread.start();
    IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    ctx.registerReceiver(this, filter);
  }

  /**
   * Stops the kicker thread and releases the wifi manager.
   */
  private synchronized void stop() {
    ctx.unregisterReceiver(this);
    listeners.clear();
    isRunning = false;
    kickerThread.interrupt();  // wake the thread up so that it dies more quickly
  }

  /**
   * Adds the given listener to receive future scans, continuously.
   *
   * @param context A context, which is required to run the scanner if it is not
   *        already running. Note that it is presumed that this will be an
   *        activity context that will live for the life of the VM. If it ever
   *        isn't, retaining this reference might cause some leaks.
   * @param listener The listener who wants to get scan results.
   */
  public static void addListener(Context context, Listener listener) {
    addListener(context, listener, 0L);
  }

  /**
   * Same as above, but indicates that the listener is interested in the given
   * minimum scan rate. Note that listeners may receive scans more often than
   * this, but will not receive scans less often than this. Also note that any
   * value less than the SCAN_TIMEOUT is considered to be a request for
   * continuous scanning.
   */
  public static synchronized void addListener(Context ctx, Listener listener, long minRate) {
    if (instance == null) {
      // There is no scanner right now, so start one up.
      instance = new WifiScanner(ctx);
    }
    instance.registerListener(listener, minRate);
  }

  /**
   * Registers the given listener, and also updates the scan interval if needed.
   */
  private synchronized void registerListener(Listener listener, long minRate) {
    instance.listeners.add(Pair.create(listener, minRate));
    if (instance.scanInterval > minRate) {
      // speed up the scan for this new listener, and wake up the kicker thread
      // in case it's currently sleeping too long.
      instance.scanInterval = minRate;
      instance.kickerThread.interrupt();
    }
  }

  /**
   * Removes the given listener from receiving scans. If this was the last
   * listener interested in wifi scans, then the scanner is stopped.
   */
  public static synchronized void removeListener(Listener listener) {
    if (instance != null && instance.unregisterListener(listener)) {
      // This was the last listener, so we can now shut this down completely.
      instance.stop();
      instance = null;
    }
  }

  /**
   * Unregisters the given listener, and also updates the scan interval if
   * needed. Returns true if there's nobody left listening now.
   */
  private synchronized boolean unregisterListener(Listener listener) {
    for (Pair<Listener, Long> p : listeners) {
      if (p.first == listener) {
        // this is the one we want to remove
        listeners.remove(p);
        
        if (p.second <= scanInterval) {
          // The scan interval may need to be slowed down now that this listener is gone.
          scanInterval = getMinimumListenerRate();
        }
        break;
      }
    }
    
    return listeners.isEmpty();
  }

  /**
   * Returns the lowest scan interval requested amongst the listeners.
   */
  private synchronized long getMinimumListenerRate() {
    long least = Long.MAX_VALUE;
    for (Pair<Listener, Long> p : listeners) {
      if (p.second < least) {
        least = p.second;
      }
    }
    return least;
  }
  
  /**
   * Returns true if one of the listeners has requested continuous scanning mode.
   */
  private synchronized boolean isContinuousScanning() {
    return scanInterval < SCAN_TIMEOUT;
  }

  /**
   * Called when the wifi manager broadcasts.
   */
  @Override
  public synchronized void onReceive(Context context, Intent intent) {
    lastResultTime = System.currentTimeMillis();

    List<ScanResult> results = manager.getScanResults();
    for (Pair<Listener, Long> listener : listeners) {
      listener.first.onScanResult(results);
    }

    if (isContinuousScanning()) {
      // the scanner is in continuous mode, so just scan immediately.
      manager.startScan();
    }
  }

  /**
   * The body of the kicker thread's run loop, which starts a scan if it's been
   * long enough.
   */
  public void run() {
    while (isRunning) {
      final long now = System.currentTimeMillis();

      if (!isContinuousScanning() || lastResultTime < now - SCAN_TIMEOUT) {
        // Either it's time to do a periodic scan, or else the continuous scan has stalled.
        manager.startScan();
      }

      // Sleep for a while.
      try {
        Thread.sleep(Math.max(scanInterval, SCAN_TIMEOUT));
      } catch (InterruptedException e) {
        // Okay, fine, stop sleeping.
      }
    }
  }
}
