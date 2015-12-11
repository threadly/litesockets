package org.threadly.litesockets.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.DatagramChannel;

public class TestUtils {

  public static int findTCPPort() {
    try {
      ServerSocket s = new ServerSocket(0);
      int port = s.getLocalPort();
      s.close();
      return port;
    } catch(IOException e) {
      //We Dont Care
    }
    throw new RuntimeException("Could not find a port!!");
  }
  
  public static int findUDPPort() {
    try {
      DatagramChannel channel = DatagramChannel.open();
      channel.socket().bind(new InetSocketAddress("127.0.0.1", 0));
      int port = channel.socket().getLocalPort();
      channel.close();
      return port;
    } catch(IOException e) {
      //We Dont Care
    }
  throw new RuntimeException("Could not find a port!!");
}
}
