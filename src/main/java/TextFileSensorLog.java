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



import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.telephony.NeighboringCellInfo;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A sensor log that produces a simple, line-oriented text file. The stream
 * auto-flushes periodically to avoid data loss if the log is not properly
 * closed.
 */
public class TextFileSensorLog extends BaseSensorLog {
  // We've been asked not to log SSIDs, but this log file format asks for them.
  // So we log "unknown" for the SSIDs of every access point we see.
  private static final String UNKNOWN_SSID = "UNKNOWN";
  private static final String LOG_FORMAT = "3 # nanosecond timing";

  // If we see this prefix on an AP, we don't log that MAC address. This
  // implements our AP opt-out feature, announced here:
  // http://googleblog.blogspot.com/2011/11/greater-choice-for-wireless-access.html
  private static final String OPTOUT_SSID_SUFFIX = "_nomap";

  // Fudge factor to allow us to compile under Froyo. Copied from the
  // Gingerbread source.
  private static final int SENSOR_LINEAR_ACCELERATION = 10;

  private static final Set<Character> AD_HOC_HEX_VALUES =
      new HashSet<Character>(Arrays.asList('2','6', 'a', 'e', 'A', 'E'));

  // An override for the timestamp to write into the log file.  Only used for testing.
  private static long overrideTimestamp = -1L;  // means unused.

  // The file we're writing.
  private final File file;

  // A stream that's writing to our text file.
  private PrintStream out;

  // The current line count.  Used to flush the stream every 100 lines.
  private int lineCount;

  /**
   * Opens the given file for writing this sensor log.
   */
  public TextFileSensorLog(File file) throws IOException {
    this(file, "");
  }

  /**
   * Opens the given file for writing this sensor log, writing the given line of
   * notes to the file as it is opened.
   */
  public TextFileSensorLog(File file, String notes) throws IOException {
    this(file, notes, false);
  }

  /**
   * Opens the given file for writing this sensor log, writing the given line of
   * notes to the file as it is opened.
   *
   * @param resume if true, the file is appended rather than overwritten. The
   *        metadata is written again, with a note that an append has occurred.
   */
  public TextFileSensorLog(File file, String notes, boolean resume) throws IOException {
    this.file = file;

    // Ensure this file and its parent directory exists
    File parent = file.getParentFile();
    parent.mkdirs();

    // start writing
    out = new PrintStream(new BufferedOutputStream(new FileOutputStream(file, resume)), false);

    // Log some meta-data
    logNote("metadata_log_format", LOG_FORMAT);
    logNote("metadata_system_time",
        String.valueOf(overrideTimestamp == -1 ? System.currentTimeMillis() : overrideTimestamp));
    logNote("metadata_surveyName", file.getAbsolutePath());
    logNote("metadata_notes", notes.replaceAll("[\\n\\r\\f]", " "));
    logNote("metadata_deviceInfo", getDeviceInfo());
    if (resume) {
      logNote("metadata_append", "file was opened for append.");
    }
  }

  private String getDeviceInfo() {
    StringBuilder builder = new StringBuilder();
    builder.append("Board: ").append(android.os.Build.BOARD);
    builder.append(" Brand: ").append(android.os.Build.BRAND);
    builder.append(" Device: ").append(android.os.Build.DEVICE);
    builder.append(" Hardware: ").append(android.os.Build.HARDWARE);
    builder.append(" Manufacturer: ").append(android.os.Build.MANUFACTURER);
    builder.append(" Model: ").append(android.os.Build.MODEL);
    builder.append(" Product: ").append(android.os.Build.PRODUCT);
    return builder.toString();
  }

  public File getFile() {
    return file;
  }

  @Override
  public void close() {
    if (out != null) {
      out.close();
    }
    out = null;
  }

  @Override
  protected void logGpsPosition(long absoluteTimeNanos, Location loc) {
    writeLocationLine(absoluteTimeNanos, "latLngE7Gps", loc);
  }

  @Override
  protected void logNetworkPosition(long absoluteTimeNanos, Location loc) {
    writeLocationLine(absoluteTimeNanos, "latLngE7Network", loc);
  }

  @Override
  protected void logGpsNmeaDataNanos(long absoluteTimeNanos, String nmeaData) {
    writeLine(absoluteTimeNanos, "rawNmea", toFileString(nmeaData.trim()));
  }

  @Override
  protected void logLastKnownPosition(long absoluteTimeNanos, Location loc) {
    writeLocationLine(absoluteTimeNanos, "latLngE7LastKnown", loc);
  }

  @Override
  protected void logManualPosition(long absoluteTimeNanos, long latE7, long lngE7) {
    writeLine(absoluteTimeNanos, "latLngE7Marker", "" + latE7 + " " + lngE7);
  }

  @Override
  protected void logPredictedPosition(
      long absoluteTimeNanos, long latE7, long lngE7, float accuracy) {
    writeLine(absoluteTimeNanos, "latLngE7Predicted", "" + latE7 + " " + lngE7 + " " + accuracy);
  }

  @Override
  protected void logUndoManualPosition(long absoluteTimeNanos) {
    writeLine(absoluteTimeNanos, "latLngE7Marker", "CANCEL_LAST_MARKER");
  }

  @Override
  protected void logNote(long absoluteTimeNanos, String noteType, String note) {
    // ensure that notes don't have characters that would bust the file format,
    // like semicolons and newlines.
    writeLine(absoluteTimeNanos, toFileString(noteType), toFileString(note));
  }

  @Override
  protected synchronized void logSensorEvent(long absoluteTimeNanos, SensorEvent event) {
    writeTimestamp(absoluteTimeNanos);
    writeSensorId(getSensorName(event.sensor.getType()), 
      event.sensor.getName());

    final int vCount = event.values.length;
    final float[] values = event.values;

    // write the first sensor value
    if (vCount > 0) {
      out.print(values[0]);
    }
    
    // write the rest of the sensor values, space-separated
    for (int i = 1; i < vCount; ++i) {
      out.print(" ");
      out.print(values[i]);
    }
    
    // Now print out the accuracy
    out.print(" "); 
    out.print(event.accuracy); 
    
    finishLogLine();
  }
 

  @Override
  protected void logWifiScan(long absoluteTimeNanos, Iterable<ScanResult> scans) {
    StringBuffer dataString = new StringBuffer();
    for (ScanResult sr : scans) {
      // NOTE: We have set the SSID to be a constant in the below.
      // This is to maintain backwards compatibility with existing formats

      if (shouldLog(sr)) {
        dataString.append(sr.BSSID + "," + UNKNOWN_SSID + "," + sr.level + " ");
      }
    }

    writeLine(absoluteTimeNanos, "wifi", dataString.toString());
  }
  
  /**
   * Returns true if the given scan should be logged, or false if it is an
   * ad-hoc AP or if it is an AP that has opted out of Google's collection
   * practices.
   */
  private static boolean shouldLog(final ScanResult sr) {
    // We filter out any ad-hoc devices.  Ad-hoc devices are identified by having a
    // 2,6,a or e in the second nybble.
    // See http://en.wikipedia.org/wiki/MAC_address -- ad hoc networks
    // have the last two bits of the second nybble set to 10.
    // Only apply this test if we have exactly 17 character long BSSID which should
    // be the case.
    final char secondNybble = sr.BSSID.length() == 17 ? sr.BSSID.charAt(1) : ' ';

    if(AD_HOC_HEX_VALUES.contains(secondNybble)) {
      return false;

    } else if (sr.SSID != null && sr.SSID.endsWith(OPTOUT_SSID_SUFFIX)) {
      return false;

    } else {
      return true;
    }
  }

  @Override
  protected void logTelephonyScan(long absoluteTimeNanos, List<NeighboringCellInfo> scan) {
    if (scan == null) {
      return;  // nothing to log
    }

    StringBuilder b = new StringBuilder();
    for (NeighboringCellInfo info : scan) {
      b.append("\"" + info.toString() + "\" ");
    }

    writeLine(absoluteTimeNanos, "telephony", b.toString());
  }

  /**
   * Writes a location line of the given type to the log file, unpacking the
   * location object into E7 lat, lng, and accuracy.
   */
  private void writeLocationLine(long absoluteTimeNanos, String key, Location loc) {
    long latE7 = (long) (loc.getLatitude() * 1e7);
    long lngE7 = (long) (loc.getLongitude() * 1e7);
    float accuracy = loc.getAccuracy();
    float bearing = loc.hasBearing() ? loc.getBearing() : -1.0f;
    float speed = loc.hasSpeed() ? loc.getSpeed() : -1.0f;

    writeLine(absoluteTimeNanos, key, "" + latE7 + " " + lngE7 + " " + accuracy + " " + bearing
        + " " + speed);
  }

  /**
   * Writes a line to the log file.
   */
  private synchronized void writeLine(long absoluteTimeNanos, String sensor, String value) {
    writeTimestamp(absoluteTimeNanos);
    writeSensorType(sensor);
    out.print(value);
    finishLogLine();
  }

  /**
   * Writes the timestamp prefix and separator to the log file.
   */
  private void writeTimestamp(long absoluteTimeNanos) {
    if (overrideTimestamp != -1) {
      absoluteTimeNanos = overrideTimestamp;  // this only happens during tests
    }

    out.print(absoluteTimeNanos);
    out.print(";");
  }
  
  /**
   * Writes the sensor name type and separator to the log file.
   */
  private void writeSensorType(String sensorType) {
    out.print(sensorType);
    out.print(";");
  }
  
  /**
   * Writes the sensor type, sensor name and separator to the log file. 
   */
  private void writeSensorId(String sensorType, String sensorName) {
    out.print(sensorType);
    out.print("/"); 
    out.print(sensorName); 
    out.print(";");
  }

  /**
   * Writes a newline to the log file, and updates the line counters. Handles
   * flushing.
   */
  private void finishLogLine() {
    out.print("\n");

    // flush the buffer every 100 lines
    lineCount++;
    if ((lineCount % 100) == 0) {
      out.flush();
    }
  }

  /**
   * Returns the text we should write to the file for a given sensor ID.
   */
  private static String getSensorName(int sensorId) {
    switch (sensorId) {
      case Sensor.TYPE_ACCELEROMETER:
        return "accel";
      case Sensor.TYPE_GYROSCOPE:
        return "gyro";
      case Sensor.TYPE_MAGNETIC_FIELD:
        return "compass";
      case Sensor.TYPE_ORIENTATION:
        return "orientation";
      case SENSOR_LINEAR_ACCELERATION:
        return "linaccel";
    }
    return "?";
  }
  
  /**
   * Returns the given text as-is, except that any newline or semi-colon
   * characters are replaced with spaces.
   */
  private static String toFileString(String text) {
    return text.replaceAll("[;\\n\\r\\f]", " ");
  }

  /**
   * Installs a timestamp that will be used for all invocations of writeLine()
   * following this. Only used for testing.
   *
   * @param overrideTimestamp the timestamp to put into the file instead of the real collection
   * time, or -1 to restore normal timestamp logging.
   */
  static void setTimestampForTest(long overrideTimestamp) {
    TextFileSensorLog.overrideTimestamp = overrideTimestamp;
  }
}
