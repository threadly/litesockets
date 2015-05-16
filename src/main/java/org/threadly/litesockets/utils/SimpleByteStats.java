package org.threadly.litesockets.utils;

import org.threadly.util.Clock;


/**
 * Simple class for trying byteStats.  This implementation only tracks global stats. 
 */
public abstract class SimpleByteStats {
  private volatile long startTime = Clock.lastKnownForwardProgressingMillis();
  private volatile long bytesRead = 0;
  private volatile long bytesWritten = 0;
  

  public SimpleByteStats() {
  }
  
  protected void addWrite(int size) {
    bytesWritten+=size;
  }
  
  protected void addRead(int size) {
    bytesRead+=size;
  }
  
  /**
   * @return the total bytes marked as Read since creation.
   */
  public long getTotalRead() {
    return bytesRead;
  }

  /**
   * @return the total bytes marked as Written since creation.
   */
  public long getTotalWrite() {
    return bytesWritten;
  }
    
  /**
   * @return the average rate per second that byte have been read, since creation.
   */
  public double getReadRate() {
    double sec = (Clock.lastKnownForwardProgressingMillis() - startTime)/1000.0;
    return (bytesRead/sec);
  }
  
  /**
   * @return the average rate per second that byte have been written, since creation.
   */
  public double getWriteRate() {
    double sec = (Clock.lastKnownForwardProgressingMillis() - startTime)/1000.0;
    return (bytesWritten/sec);
  }
}

