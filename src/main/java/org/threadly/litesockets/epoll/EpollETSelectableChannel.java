package org.threadly.litesockets.epoll;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class EpollETSelectableChannel implements Closeable {
  
  private final long channelId;
  private final AtomicBoolean isClosed = new AtomicBoolean(false);
  
  EpollETSelectableChannel(long channelId) {
    this.channelId = channelId;
  }

  long getFD() {
    return channelId;
  }
  
  protected abstract void closeSocket() throws IOException;
  
  public boolean isClosed() {
    return isClosed.get();
  }
  
  public boolean isOpen() {
    return !isClosed.get();
  }

  @Override
  public void close() throws IOException {
    if(isClosed.compareAndSet(false, true)) {
      closeSocket();
    }
  }
}
