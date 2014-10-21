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






import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.GpsStatus.NmeaListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the phone's sensors and gathers the data they produce into a given
 * log. The SensorCollector has no impact until it is start()ed. A collection
 * can be paused and resumed many times, but it can only be close()ed once.
 * After that, it is illegal to resume() or start() it again.
 * 
 * When pause()ed, the sensors and threads are turned off, and no overhead is
 * used. However, the log is not closed when pause() occurs, so resume() will
 * continue to append to the same log.
 * 
 * Note that because this object is heavy on phone resources, it's important
 * that you call pause() or close() from your activity's onPause() method.
 * Otherwise it will continue consuming the phone's sensors, radio, and battery.
 */
public class SensorCollector
    implements
      SensorEventListener,
      Closeable,
      WifiScanner.Listener,
      LocationListener,
      NmeaListener {

  // Fudge factor to allow us to compile under Froyo. We copy the constant from
  // Gingerbread source.
  private static final int SENSOR_LINEAR_ACCELERATION = 10;

  // Some constants
  private static final String TAG = "SensorCollector";

  // The log that we'll be writing to.
  private final SensorLog log;

  // The context wrapper that we'll use for accessing system services, receiving
  // broadcasts from the WifiManager, etc.
  private final Context context;

  // Lifecycle booleans
  private boolean isStarted = false;
  private boolean isCollecting = false;
  private boolean isClosed = false;
  
  // Gyro preferences. 
  private boolean isFastGyro = false;

  // Phone sensor and service managers
  private final SensorManager sensorManager;
  private final LocationManager locationManager;

  /**
   * Creates a collector that will write to the given file.
   */
  public SensorCollector(Context context, File sensorLogFile) throws IOException {
    this(context, new TextFileSensorLog(sensorLogFile));

  }

  /**
   * Creates a collector that will write to the given log.
   */
  public SensorCollector(Context context, SensorLog log) {
    if (log == null || context == null) {
      throw new NullPointerException();
    }

    this.context = context;
    this.log = log;
    // acquire references to the phone's managers
    sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
  }

  /**
   * Returns the log that this collector is writing to.
   */
  public SensorLog getLog() {
    return log;
  }

  /**
   * Starts the collector. Sensors and wifi scan passes will be activated, and
   * their results will be written through to the given log. Calling close()
   * will stop the collection and close the log.
   */
  public synchronized void start() {
    expectNotClosed();

    // record the last known location on survey start, so that we have
    // at least some idea of the location of the survey.
    String p = locationManager.getBestProvider(new Criteria(), true);
    Location loc = p != null ? locationManager.getLastKnownLocation(p) : null;
    if (loc != null) {
      log.logLastKnownPosition(loc);
    }

    startCollectors();
    isStarted = true;
  }

  /**
   * Pauses the collectors but does not close the log. Automatically writes a
   * note to the log that a pause has occurred.
   * 
   * Note that pausing the sensors will create a discontinuity in (for example)
   * the gyro readings, which will make it impossible to integrate their values
   * across this point. To make the surveys more useful, I'd expect that you
   * want to instruct your users to do something to make interpretation of these
   * gaps more consistent. For example, you could instruct a user to face
   * north whenever they pause or resume a survey.
   */
  public synchronized void pause() {
    expectStarted();
    if (!isCollecting) {
      return; // already paused, ignore.
    }

    stopCollectors();
    log.logNote("pause", "collection paused.");
  }

  /**
   * Resumes the collectors if they were previously paused. Automatically writes
   * a note to the log that a resume has occurred.
   */
  public synchronized void resume() {
    expectStarted();
    if (isCollecting) {
      return; // already resumed, ignore.
    }

    log.logNote("resume", "collection resuming.");
    startCollectors();
  }

  /**
   * Shuts down all collectors and closes the log.  Cannot fail.
   */
  public synchronized void close() {
    expectStarted();
    stopCollectors();
    log.close();
    isClosed = true;
  }

  /**
   * Returns true if the collector has ever been started.
   */
  public boolean isStarted() {
    return isStarted;
  }

  /**
   * Returns true if the collector is currently collecting, or false if it is
   * paused.
   */
  public boolean isCollecting() {
    return isCollecting;
  }

  /**
   * Returns true if the collector has been closed.
   */
  public boolean isClosed() {
    return isClosed;
  }

  /**
   * Asks if the gyro is in high speed mode
   */
  public synchronized boolean isFastGyro() {
    return isFastGyro;
  }

  /**
   * Set the gyro to high speed mode. Becomes enabled on the next start
   * collecting call.
   */
  public synchronized void setFastGyro(boolean isFastGyro) {
    this.isFastGyro = isFastGyro;
  }

  /**
   * Returns true if the collector is started, not closed, but not currently
   * collecting.
   */
  public boolean isPaused() {
    return isStarted && !isClosed && !isCollecting;
  }

  /**
   * Starts up the collector processes, which will run the phone's sensors and
   * wifi scanner.
   */
  private synchronized void startCollectors() {
    if (isCollecting) {
      return; // collectors already running, ignore.
    }

    Log.i(TAG, "starting sensors, GPS, and wifi scanner.");

    // subscribe to all of the sensors, which turns them on.
    for (Sensor s : getSensorList()) {
      if (s.getType() == Sensor.TYPE_GYROSCOPE) {
        if (isFastGyro) {
          sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST);
        } else {
          sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
        }
      } else {
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST);
      }
    }

    // Request Location updates from the GPS and cell towers
    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this);
    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
    locationManager.addNmeaListener(this);

    // start up the wifi scanner
    WifiScanner.addListener(context, this);

    isCollecting = true;
  }

  /**
   * Shuts down all collectors. Used during pause() and close().
   */
  private synchronized void stopCollectors() {
    if (!isCollecting) {
      return; // collectors already stopped, ignore.
    }

    // stop asking for location updates
    locationManager.removeUpdates(this);
    locationManager.removeNmeaListener(this);

    // stop asking for sensor data, wifi scans, etc
    WifiScanner.removeListener(this);
    sensorManager.unregisterListener(this);
    isCollecting = false;

    Log.i(TAG, "stopped sensors and wifi scanner.");
  }

  /**
   * Called when the sensor capture receives an accuracy changed event.
   */
  @Override
  public synchronized void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  /**
   * Called when the sensor capture receives a sensor event.
   */
  @Override
  public synchronized void onSensorChanged(SensorEvent event) {
    if (isCollecting) {
      log.logSensorEvent(event);
    }
  }

  /**
   * Called when the wifi scanner has a list of results.
   */
  @Override
  public void onScanResult(List<ScanResult> results) {
    if (isCollecting) {
      log.logWifiScan(results);
    }
  }

  /**
   * Called when there's a new location generated from the GPS or network
   * provider.
   */
  @Override
  public void onLocationChanged(Location loc) {
    if (!isCollecting) {
      return; // ignore this event
    }

    if (LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
      log.logGpsPosition(loc);
    } else if (LocationManager.NETWORK_PROVIDER.equals(loc.getProvider())) {
      log.logNetworkPosition(loc);
    } else {
      throw new RuntimeException("Unknown provider: " + loc);
    }
  }

  /**
   * Called when there's raw NMEA data to record from the GPS radio.
   */
  @Override
  public void onNmeaReceived(long timestampMs, String data) {
    if (!isCollecting) {
      return; // ignore this event
    }

    log.logGpsNmeaData(timestampMs, data);
  }

  @Override
  public void onProviderDisabled(String unused) {
    // Ignore
  }

  @Override
  public void onProviderEnabled(String unused) {
    // Ignore
  }

  @Override
  public void onStatusChanged(String unused, int alsoUnused, Bundle seriously) {
    // Ignore
  }

  /**
   * Returns all of the sensors from the gyro, accel, and compass sensor
   * managers.
   */
  private List<Sensor> getSensorList() {
    List<Sensor> sensors = new ArrayList<Sensor>();
    sensors.addAll(sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE));
    sensors.addAll(sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER));
    sensors.addAll(sensorManager.getSensorList(Sensor.TYPE_MAGNETIC_FIELD));
    sensors.addAll(sensorManager.getSensorList(Sensor.TYPE_ORIENTATION));
    // Fudge factor to get this to compile under Froyo, while
    // still allowing for us to take advantage of Gingerbread.
    // Investigation of froyo source indicates that this doesn't cause a
    // crash or an exception to be thrown.
    sensors.addAll(sensorManager.getSensorList(SENSOR_LINEAR_ACCELERATION));
    return sensors;
  }

  /**
   * Throws an exception if this collector is closed.
   */
  private void expectNotClosed() {
    if (isClosed) {
      throw new IllegalStateException("Collector is already closed.");
    }
  }

  /**
   * Throws an exception if this collector is not started, or if it is closed.
   */
  private void expectStarted() {
    if (!isStarted) {
      throw new IllegalStateException("Collector is not started.");
    }
    if (isClosed) {
      throw new IllegalStateException("Collector is already closed.");
    }
  }
}
