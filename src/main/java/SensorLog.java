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



import android.hardware.SensorEvent;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.telephony.NeighboringCellInfo;

import java.io.Closeable;
import java.util.List;

/**
 * Accepts and stores a time-ordered sequence of specific types of sensor read
 * events.
 *
 * Timestamps are always expressed in nanosecond precision. All time arguments
 * are expected to be relative to results from java.lang.System.nanoTime().
 * Although android.hardware.SensorEvent.timestamp is not documented to be
 * relative to this time, it is.
 *
 * Note that this interface accepts data in structured form so that it will
 * later be possible to store the sensor data in a structured way, e.g. JSON,
 * Proto, or another sensor collection library.
 */
public interface SensorLog extends Closeable {

  /**
   * Writes a textual note to the sensor log with the current time. It is
   * important not to use this method if there is a more structured sensor
   * logging method to use. See below.
   */
  public void logNote(String noteType, String note);

  /**
   * Records the phone's precisely known geographic position on the earth at
   * this moment, as determined by a human operator.
   */
  public void logManualPosition(long latE7, long lngE7);

  /**
   * Records the current prediction of the phone's location based on the
   * experimental prediction model that the phone is currently running. This is
   * used during validation surveys to do field measurements of the predictor's
   * accuracy.
   * 
   * @param accuracy The 95th percentile accuracy of the prediction, in meters.
   */
  public void logPredictedPosition(long latE7, long lngE7, float accuracy);

  /**
   * Records that the human operator considers the most recently logged
   * non-erroneous manual position to be erroneous. That is, they wish to "undo"
   * the last call to logManualPosition.
   *
   * If two calls to logUndoManualPosition() occur before the next
   * logManualPosition, then the last TWO calls to logManualPosition() should be
   * ignored. 3 consecutive calls means that the last three calls to
   * logManualPosition() should be ignored, and so on.
   */
  public void logUndoManualPosition();

  /**
   * Records the phone's current geographic position according to the Android
   * LocationManager's GPS provider.
   */
  public void logGpsPosition(Location loc);

  /**
   * Records the phone's current geographic position according to the Android
   * LocationManager's network provider.
   */
  public void logNetworkPosition(Location loc);

  /**
   * Records an NMEA data string returned from the GPS location provider.
   *
   * @param originalTimestamp The supplied timestamp should be precisely the
   *        value of the timestamp passed to
   *        GpsStatus.NmeaListener.onNmeaReceived. It should not be the system
   *        clock, the uptime, or any other derivation. Through testing I've
   *        convinced myself that this is just system millis.
   */
  public void logGpsNmeaData(long originalTimestamp, String nmea);

  /**
   * Records the phone's last known geographic position according to the Android
   * LocationManager's best provider.
   */
  public void logLastKnownPosition(Location loc);

  /**
   * Records the given Android hardware sensor event at the time at which it
   * occurred (e.g. its timestamp field).
   */
  public void logSensorEvent(SensorEvent event);

  /**
   * Records an 802.11 access point scan.
   */
  public void logWifiScan(Iterable<ScanResult> scan);
  
  /**
   * Records the list of neighboring cell towers.
   */
  public void logTelephonyScan(List<NeighboringCellInfo> scan);

  /**
   * Closes the given log. It is an error to make further use of the log after
   * this method has been called.
   */
  public void close();
}
