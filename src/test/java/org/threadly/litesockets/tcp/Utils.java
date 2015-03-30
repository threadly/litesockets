package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;

import org.junit.Test;
import org.threadly.concurrent.SingleThreadScheduler;
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
  /*
  @Test
  public void test() throws Exception {
    SingleThreadScheduler STS = new SingleThreadScheduler(); 
    TCPServer server = new TCPServer("localhost", 9089);
    final Selector selector = Selector.open();
    SocketChannel sc = SocketChannel.open();
    sc.configureBlocking(false);
    sc.connect(new InetSocketAddress("localhost", 9088));
    Thread.sleep(2000);
    final SelectionKey mk = sc.register(selector, SelectionKey.OP_CONNECT);
    selector.select(10000);
    for(SelectionKey key: selector.selectedKeys()) {
      System.out.println(key.isConnectable());
      SocketChannel sc2 = (SocketChannel)key.channel();
      System.out.println(sc2.isConnectionPending());
      System.out.println(sc2.finishConnect());
    }
    System.out.println(selector.keys());
    
  }*/
  
}
