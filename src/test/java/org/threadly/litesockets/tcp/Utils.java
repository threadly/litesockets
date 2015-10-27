package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.DatagramChannel;
import java.util.Random;

public class Utils {
  static Random rnd = new Random();
  
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
