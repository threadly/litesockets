package org.threadly.litesockets.utils;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.DatagramChannel;

/**
 * Simple helper functions for dealing with ports.
 * 
 * @author lwahlmeier
 *
 */
public class PortUtils {
  
  private PortUtils(){};

  /**
   * Returns an available TCP port.
   * 
   * @return a free tcp port number.
   */
  public static int findTCPPort() {
    try {
      ServerSocket s = new ServerSocket(0);
      s.setReuseAddress(true);
      int port = s.getLocalPort();
      s.close();
      return port;
    } catch(IOException e) {
      //We Dont Care
    }
    throw new RuntimeException("Could not find a port!!");
  }
  
  /**
   * Returns an available UDP port.
   * 
   * @return a free udp port number.
   */
  public static int findUDPPort() {
    try {
      DatagramChannel channel = DatagramChannel.open();
      channel.socket().bind(new InetSocketAddress("0.0.0.0", 0));
      channel.socket().setReuseAddress(true);
      int port = channel.socket().getLocalPort();
      channel.close();
      return port;
    } catch(IOException e) {
      //We Dont Care
    }
    throw new RuntimeException("Could not find a port!!");
  }
  
  public static void closeQuietly(Closeable closer) {
    try {
      closer.close();
    } catch(Throwable t) {
      
    }
  }
}
