package org.threadly.litesockets.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EmuJavaTests {
  static String testString = "";
  static {
    StringBuilder sb = new StringBuilder();
    for(int i=0; i<1000; i++) {
      sb.append("1234567890");
    }
    testString = sb.toString();
  }
  InetSocketAddress localISA = new InetSocketAddress("127.0.0.1", 13723);

  Selector selector;
  SelectorProvider sp;
  ServerSocketChannel ssc;
  SocketChannel client1;
  SocketChannel client2;

  @Before
  public void start() throws IOException {
//    selector = Selector.open();
//    sp = selector.provider();

    selector = EmuSelector.open();
    sp = selector.provider();
    ssc = null;
    client1 = null;
    client2 = null;
  }

  @After
  public void end() throws IOException {
    selector.close();
    client1.close();
    client2.close();
    ssc.close();
  }

  @Test
  public void blocking() throws IOException {
    ssc = sp.openServerSocketChannel();
    ssc.bind(localISA, 5);
    ssc.configureBlocking(true);
    client1 = sp.openSocketChannel();
    client1.connect(localISA);
    client2 = ssc.accept();
    for(int i=0; i<1000; i++) {
      int test = client2.write(ByteBuffer.wrap(testString.getBytes()));
      ByteBuffer bb = ByteBuffer.allocate(testString.length()+20);
      client1.read(bb);
    }
  }

  @Test
  public void nonBlocking() throws IOException {
    ssc = sp.openServerSocketChannel();
    ssc.bind(localISA, 5);
    ssc.configureBlocking(false);
    SelectionKey ssk = ssc.register(selector, SelectionKey.OP_ACCEPT);
    assertEquals(ssk, ssc.register(selector, SelectionKey.OP_ACCEPT));
    client1 = sp.openSocketChannel();
    client1.configureBlocking(false);
    client1.connect(localISA);
    SelectionKey c2sk = null;
    int test = selector.select(1000);
    assertEquals(1, test);
    Set<SelectionKey> sks = selector.selectedKeys();
    for(SelectionKey nsk: sks) {
      if(nsk.isAcceptable()) {
        assertEquals(ssk, nsk);
        client2 = ((ServerSocketChannel)nsk.channel()).accept();
        client2.configureBlocking(false);
        c2sk = client2.register(selector, SelectionKey.OP_READ);
      }
    }
    SelectionKey csk = client1.register(selector, SelectionKey.OP_CONNECT);
    test = selector.select(1000);
    assertEquals(1, test);
    sks = selector.selectedKeys();
    for(SelectionKey nsk: sks) {
      if(nsk.isConnectable()) {
        assertEquals(csk, nsk);
        assertTrue(((SocketChannel)nsk.channel()).finishConnect());
      }
    }
    csk.interestOps(SelectionKey.OP_READ);
    for(int i=0; i<1000; i++) {
      c2sk.interestOps(SelectionKey.OP_READ|SelectionKey.OP_WRITE);

      test = selector.select(1000);
      assertEquals(1, test);
      sks = selector.selectedKeys();
      for(SelectionKey nsk: sks) {
        if(nsk.isWritable()) {
          assertEquals(c2sk, nsk);
          int w = ((SocketChannel)nsk.channel()).write(ByteBuffer.wrap(testString.getBytes()));
          assertEquals(testString.length(), w);
          nsk.interestOps(SelectionKey.OP_READ);
        }
      }


      ByteBuffer bb = ByteBuffer.allocate(testString.length()+20);
      test = selector.select(1000);
      assertEquals(1, test);
      sks = selector.selectedKeys();
      for(SelectionKey nsk: sks) {
        if(nsk.isReadable()) {
          assertEquals(csk, nsk);
          int r = ((SocketChannel)nsk.channel()).read(bb);
          assertEquals(10000, r);
        }
      }
    }
    ssc.close();
  }
  
  

}
