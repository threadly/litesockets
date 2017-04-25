package org.threadly.litesockets.utils;

import java.util.concurrent.atomic.LongAdder;

import org.threadly.util.Clock;


/**
 * Simple class for trying byteStats.  This implementation only tracks global stats. 
 */
public class SimpleByteStats {
  private final LongAdder bytesRead = new LongAdder();
  private final LongAdder bytesWritten = new LongAdder();
  
  private volatile long startTime = Clock.lastKnownForwardProgressingMillis();

  public SimpleByteStats() {
    //Nothing needed
  }
  
  protected void addWrite(final int size) {
    bytesWritten.add(size);
  }
  
  protected void addRead(final int size) {
    bytesRead.add(size);
  }
  
  /**
   * @return the total bytes marked as Read since creation.
   */
  public long getTotalRead() {
    return bytesRead.sum();
  }

  /**
   * @return the total bytes marked as Written since creation.
   */
  public long getTotalWrite() {
    return bytesWritten.sum();
  }
    
  /**
   * @return the average rate per second that byte have been read, since creation.
   */
  public double getReadRate() {
    final double sec = (Clock.lastKnownForwardProgressingMillis() - startTime)/1000.0;
    return (bytesRead.sum()/sec);
  }
  
  /**
   * @return the average rate per second that byte have been written, since creation.
   */
  public double getWriteRate() {
    final double sec = (Clock.lastKnownForwardProgressingMillis() - startTime)/1000.0;
    return (bytesWritten.sum()/sec);
  }
  
  /**
   * Resets all stats.
   * 
   */
  public void resetStats() {
    startTime = Clock.lastKnownForwardProgressingMillis();
    bytesRead.reset();
    bytesWritten.reset();
  }
}
