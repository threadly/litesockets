package org.threadly.litesockets.utils;

import org.threadly.util.Clock;

public class SimpleByteStats {
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
  
  /*PUBLIC FUCTIONS*/
  public long getTotalRead() {
    return bytesRead;
  }
  
  public long getTotalWrite() {
    return bytesWritten;
  }
  
  public int getReadRate() {
    double sec = ((Clock.lastKnownForwardProgressingMillis() - startTime)/1000.0);
    return (int)(bytesRead/sec);
  }
  
  public int getWriteRate() {
    double sec = ((Clock.lastKnownForwardProgressingMillis() - startTime)/1000.0);
    return (int)(bytesWritten/sec);
  }
}

