package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.util.Random;

import org.threadly.litesockets.udp.UDPServer;

public class Utils {
  static Random rnd = new Random();
  public static int findTCPPort() {
    for(int i=0; i<200; i++) {
      int port = rnd.nextInt() & 0xffff; 
      if (port < 1024 || port > Short.MAX_VALUE*2) {
        continue;
      }
      try {
        TCPServer tmp = new TCPServer("127.0.0.1", port);
        tmp.close();
        return port;
      } catch(IOException e) {
        //We Dont Care
      }
    }
    throw new RuntimeException("Could not find a port!!");
  }
  
  public static int findUDPPort() {
    for(int i=0; i<200; i++) {
      int port = rnd.nextInt() & 0xffff; 
      if (port < 1024 || port > Short.MAX_VALUE*2) {
        continue;
      }
      try {
        UDPServer tmp = new UDPServer("127.0.0.1", port);
        tmp.close();
        return port;
      } catch(IOException e) {
        //We Dont Care
      }
    }
    throw new RuntimeException("Could not find a port!!");
  }
}
