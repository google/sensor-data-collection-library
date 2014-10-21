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

import java.util.List;

/**
 * A convenience base class for SensorLog that handles the nano-to-millis
 * conversions for creating absolute nanosecond timestamps. Note that this class
 * does not attempt to handle the nanosecond clock rolling over, presuming that
 * would only happen if the phone rebooted, which should end the log anyway.
 */
public abstract class BaseSensorLog implements SensorLog {
  public static final long NANOS_PER_MILLIS = 1000000L;

  /**
   * The number of nanonseconds between 01/01/1970 and the zero moment of
   * System.nanoTime(). Assuming that System.nanoTime() is implemented using the
   * phone's uptime, this should be considered reliable as long as this object
   * remains in memory. Not safe to serialize across reboots.
   */
  protected final long absoluteNanosOffset;

  protected BaseSensorLog() {
    long nowMillis = System.currentTimeMillis();
    long nowNanos = System.nanoTime();
    absoluteNanosOffset = nowMillis * NANOS_PER_MILLIS - nowNanos;
  }

  /**
   * Returns the current time in absolute nanoseconds.
   */
  protected final long getNowNanos() {
    return absoluteNanosOffset + System.nanoTime();
  }

  @Override
  public void logNote(String noteType, String note) {
    logNote(getNowNanos(), noteType, note);
  }

  @Override
  public void logGpsPosition(Location loc) {
    logGpsPosition(loc.getTime() * NANOS_PER_MILLIS, loc);
  }

  @Override
  public void logNetworkPosition(Location loc) {
    logNetworkPosition(loc.getTime() * NANOS_PER_MILLIS, loc);
  }

  @Override
  public void logGpsNmeaData(long timestampMs, String nmea) {
    logGpsNmeaDataNanos(timestampMs * NANOS_PER_MILLIS, nmea);
  }

  @Override
  public void logLastKnownPosition(Location loc) {
    logLastKnownPosition(loc.getTime() * NANOS_PER_MILLIS, loc);
  }

  @Override
  public void logManualPosition(long latE7, long lngE7) {
    logManualPosition(getNowNanos(), latE7, lngE7);
  }

  @Override
  public void logPredictedPosition(long latE7, long lngE7, float accuracy) {
    logPredictedPosition(getNowNanos(), latE7, lngE7, accuracy);
  }

  @Override
  public void logUndoManualPosition() {
    logUndoManualPosition(getNowNanos());
  }

  @Override
  public void logWifiScan(Iterable<ScanResult> scan) {
    logWifiScan(getNowNanos(), scan);
  }

  @Override
  public void logSensorEvent(SensorEvent event) {
    logSensorEvent(absoluteNanosOffset + event.timestamp, event);
  }

  @Override
  public void logTelephonyScan(List<NeighboringCellInfo> scan) {
    logTelephonyScan(getNowNanos(), scan);
  }

  // All of these methods are the same as the above versions, except that they log the specific
  // time (in absolute nanoseconds) instead of the current time.

  protected abstract void logNote(long absoluteTimeNanos, String noteType, String note);
  protected abstract void logManualPosition(long absoluteTimeNanos, long latE7, long lngE7);
  protected abstract void logPredictedPosition(
      long absoluteTimeNanos, long latE7, long lngE7, float accuracy);
  protected abstract void logUndoManualPosition(long absoluteTimeNanos);
  protected abstract void logGpsPosition(long absoluteTimeNanos, Location loc);
  protected abstract void logNetworkPosition(long absoluteTimeNanos, Location loc);
  protected abstract void logGpsNmeaDataNanos(long absoluteTimeNanos, String nmeaData);
  protected abstract void logLastKnownPosition(long absoluteTimeNanos, Location loc);
  protected abstract void logWifiScan(long absoluteTimeNanos, Iterable<ScanResult> scan);
  protected abstract void logSensorEvent(long absoluteTimeNanos, SensorEvent event);
  protected abstract void logTelephonyScan(long absoluteTimeNanos, List<NeighboringCellInfo> scan);
}
