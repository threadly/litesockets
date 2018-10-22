package org.threadly.litesockets.epoll;

import java.io.IOException;
import java.net.ProtocolFamily;

public class EpollETDatagramChannel extends EpollETSelectableChannel {

  private static native long openDatagramChannel();
  private static native long openDatagramChannel(ProtocolFamily family);
  
  
  EpollETDatagramChannel(long channelId) {
    super(channelId);
  }

  public static EpollETDatagramChannel open() {
    return new EpollETDatagramChannel(openDatagramChannel());
  }
  
  public static EpollETDatagramChannel open(ProtocolFamily family) {
    return new EpollETDatagramChannel(openDatagramChannel(family));
  }
  @Override
  protected void closeSocket() throws IOException {
    // TODO Auto-generated method stub
    
  }
}
