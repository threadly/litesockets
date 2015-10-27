package org.threadly.litesockets.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.TCPServer;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.test.concurrent.TestCondition;
import org.threadly.test.concurrent.TestUtils;


public class TCPTests {
  public static final String SMALL_TEXT = "TEST111";
  public static final ByteBuffer SMALL_TEXT_BUFFER = ByteBuffer.wrap(SMALL_TEXT.getBytes());
  public static final String LARGE_TEXT;
  public static final ByteBuffer LARGE_TEXT_BUFFER;
  static {
    StringBuffer sb = new StringBuffer();
    for(int i = 0; i<20000; i++) {
      sb.append(SMALL_TEXT);
    }
    LARGE_TEXT = sb.toString();
    LARGE_TEXT_BUFFER = ByteBuffer.wrap(LARGE_TEXT.getBytes());
  }
  PriorityScheduler PS;
  int port = Utils.findTCPPort();
  final String GET = "hello";
  SocketExecuter SE;
  TCPServer server;
  FakeTCPServerClient serverFC;
  
  @Before
  public void start() throws IOException {
    port = Utils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    serverFC = new FakeTCPServerClient(SE);
    server = SE.createTCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
  }
  
  @After
  public void stop() {    
    SE.stopListening(server);
    SE.stopIfRunning();
    PS.shutdown();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }

  @Test(expected=IllegalStateException.class)
  public void writeClosedSocket() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.connect().get(5000, TimeUnit.MILLISECONDS);
    client.close();
    client.write(SMALL_TEXT_BUFFER.duplicate());
  }
  
  
  @Test
  public void setClientOptions() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    assertTrue(client.setSocketOption(Client.SocketOption.TCP_NODELAY, 1));
    assertFalse(client.setSocketOption(Client.SocketOption.SEND_BUFFER_SIZE, 0));
    assertFalse(client.setSocketOption(Client.SocketOption.RECV_BUFFER_SIZE, 0));
    assertTrue(client.setSocketOption(Client.SocketOption.SEND_BUFFER_SIZE, 1));
    assertTrue(client.setSocketOption(Client.SocketOption.RECV_BUFFER_SIZE, 1));
    assertFalse(client.setSocketOption(Client.SocketOption.UDP_FRAME_SIZE, 1));
    assertFalse(client.isEncrypted());
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.setReader(clientFC);
    client.addCloseListener(clientFC);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = (TCPClient) serverFC.map.keys().nextElement();
    client.write(SMALL_TEXT_BUFFER.duplicate());
    //System.out.println("1");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test =clientFC.map.get(client).remaining() == SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(5000);
    server.close();
  }
  
  @Test
  public void simpleWriteTest() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.setReader(clientFC);
    client.addCloseListener(clientFC);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = (TCPClient) serverFC.map.keys().nextElement();
    client.write(SMALL_TEXT_BUFFER.duplicate());
    //System.out.println("1");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test =clientFC.map.get(client).remaining() == SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(5000);
    server.close();
  }

  @Test
  public void serverCreate1() throws IOException {
    server.close();
    ServerSocketChannel socket = ServerSocketChannel.open();
    socket.socket().bind(new InetSocketAddress("localhost", port), 100);
    server = SE.createTCPServer(socket);
    serverFC = new FakeTCPServerClient(SE);
    server.setClientAcceptor(serverFC);
    server.start();
    TCPClient client = SE.createTCPClient("localhost", port);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    client.close();
    server.close();
  }
  
  @Test
  public void clientBlockingWriter() throws Exception {
    final ByteBuffer bb = ByteBuffer.wrap("TEST111".getBytes());
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.setMaxBufferSize(2);
    serverFC.addTCPClient(client);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 2;
      }
    }.blockTillTrue(5000, 100);
    TCPClient c2 = (TCPClient) serverFC.clients.get(1);
    c2.setMaxBufferSize(2);
    ArrayList<ListenableFuture<?>> lfl = new ArrayList<ListenableFuture<?>>(); 
    for(int i=0; i<100; i++) {
      lfl.add(c2.write(bb.duplicate()));
    }
    FutureUtils.makeCompleteFuture(lfl).get(5000, TimeUnit.MILLISECONDS);;
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(client).remaining() == bb.remaining()*100;
      }
    }.blockTillTrue(5000, 100);
    c2.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 0;
      }
    }.blockTillTrue(5000, 100);
    server.close();
  }
  
  @Test
  public void clientRemoveReader() throws IOException, InterruptedException {
    String text = "test";
    StringBuilder sb = new StringBuilder();
    for(int i=0; i<10; i++) {
      sb.append(text);
    }
    System.out.println("1");
    ByteBuffer bb = ByteBuffer.wrap(text.toString().getBytes());
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.setMaxBufferSize(2);
    client.setReader(new Reader() {
      @Override
      public void onRead(Client client) {

      }});
    System.out.println("2");
    client.connect();
    System.out.println("3");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    System.out.println("4");
    final TCPClient c2 = (TCPClient) serverFC.clients.get(0);
//    for(Client c: serverFC.map.keySet()) {
//      c2 = (TCPClient)c;
//      break;
//    }
    
    c2.setMaxBufferSize(60000);
    System.out.println("5");
    c2.write(bb.duplicate());
    System.out.println("6");
    new TestCondition() {
      @Override
      public boolean get() {
        return ! client.canRead();
      }
    }.blockTillTrue(5000, 100);
    System.out.println("7");
    MergedByteBuffers readBB = client.getRead();
    while(readBB.remaining() > 0) {
      readBB = client.getRead();
    }
    System.out.println("8");
    server.close();
  }
  
  @Test(expected=ClosedChannelException.class)
  public void clientBadSocket1() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.close();
    TCPClient client = SE.createTCPClient(cs);
    server.close();
    client.close();
  }  
  
  @Test
  public void clientAddSocket() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = SE.createTCPClient(cs);
    server.close();
    client.close();
  }
  
  @Test
  public void clientDoubleAdd() throws IOException, InterruptedException, ExecutionException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = SE.createTCPClient(cs);
    client.connect();
    SE.setClientOperations(client);
    SE.setClientOperations(client);
    SE.setClientOperations(client);
    SE.setClientOperations(client);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(SE.getClientCount());
        return SE.getClientCount() == 2;
      }
    }.blockTillTrue(5000, 100);    
    
    client.close();
    server.close();
  }
  
  @Test//(expected=IllegalStateException.class)
  public void clientAddToStopedSE() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = SE.createTCPClient(cs);
    new TestCondition(){
      @Override
      public boolean get() {
        return SE.getClientCount() == 2;
      }
    }.blockTillTrue(5000, 100);
    //SE.addClient(client);
    SE.stop();
    SE.setClientOperations(client);
  }
  
  @Test
  public void clientAddClosed() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = SE.createTCPClient(cs);
    new TestCondition(){
      @Override
      public boolean get() {
        return SE.getClientCount() == 2;
      }
    }.blockTillTrue(5000, 100);
    client.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return SE.getClientCount() == 0;
      }
    }.blockTillTrue(5000, 100);
    assertEquals(0, SE.getClientCount());
    SE.setClientOperations(client);
    SE.setClientOperations(client);
    assertEquals(0, SE.getClientCount());
    client.close();
    server.close();
  }
  
  @Test
  public void clientStartingWrite() throws IOException, InterruptedException {
    TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.write(SMALL_TEXT_BUFFER.duplicate());
    clientFC.addTCPClient(client);
    client.connect();

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    
    TCPClient c2 = null;
    for(Client c: serverFC.map.keySet()) {
      c2 = (TCPClient)c;
      break;
    }
    final TCPClient cf = c2;
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(cf).remaining() > 0 ;
      }
    }.blockTillTrue(5000, 100);
    assertEquals(serverFC.map.get(c2).remaining(), SMALL_TEXT_BUFFER.remaining());
    assertEquals(serverFC.map.get(c2).getAsString(serverFC.map.get(c2).remaining()), SMALL_TEXT);
  }
  
  
  @Test
  public void clientLateReadStart() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    for(int i=0; i<50000; i++) {
      client.write(SMALL_TEXT_BUFFER.duplicate());  
    }
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    
    final TCPClient c2 = (TCPClient)serverFC.clients.get(0);
    //SE.removeClient(c2);
    //SE.removeClient(client);
    
    client.connect();
    c2.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        //System.out.println(client.getWriteBufferSize());
        return serverFC.map.get(c2).remaining() == SMALL_TEXT_BUFFER.remaining()*50000 ;
      }
    }.blockTillTrue(10000, 100);
  }
  
  @Test
  public void bigWrite() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.write(LARGE_TEXT_BUFFER.duplicate());
    clientFC.addTCPClient(client);
    client.write(LARGE_TEXT_BUFFER.duplicate());
    client.write(LARGE_TEXT_BUFFER.duplicate());
    client.write(LARGE_TEXT_BUFFER.duplicate());
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    
    TCPClient c2 = null;
    for(Client c: serverFC.map.keySet()) {
      c2 = (TCPClient)c;
      break;
    }
    final TCPClient cf = c2;
    new TestCondition(){
      @Override
      public boolean get() {
        //System.out.println(serverFC.map.get(cf).remaining()+":"+(LARGE_TEXT_BUFFER.remaining()*4));
        
        return serverFC.map.get(cf).remaining() == LARGE_TEXT_BUFFER.remaining()*4 ;
      }
    }.blockTillTrue(5000, 100);
    assertEquals(serverFC.map.get(c2).remaining(), LARGE_TEXT_BUFFER.remaining()*4);
    assertEquals(serverFC.map.get(c2).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    assertEquals(serverFC.map.get(c2).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    assertEquals(serverFC.map.get(c2).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    assertEquals(serverFC.map.get(c2).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    System.out.println(client.getStats().getTotalRead());
    System.out.println(client.getStats().getReadRate());
    System.out.println(client.getStats().getTotalWrite());
    System.out.println(client.getStats().getWriteRate());
    System.out.println("-----");    
    System.out.println(cf.getStats().getTotalRead());
    System.out.println(cf.getStats().getReadRate());
    System.out.println(cf.getStats().getTotalRead());
    System.out.println(cf.getStats().getReadRate());
    System.out.println(cf.getStats().getTotalWrite());
    System.out.println(cf.getStats().getWriteRate());
  }
  
  @Test(expected=ExecutionException.class)
  public void tcpBadAddress() throws IOException, InterruptedException, ExecutionException {
    TCPClient client = SE.createTCPClient("2.0.0.256", port);
    client.setConnectionTimeout(1000);
    client.connect().get();
  }
  
  @Test
  public void tcpTimeout() throws Throwable {
    TCPClient client = SE.createTCPClient("2.0.0.2", port);
    client.setConnectionTimeout(10);
    assertTrue(!client.hasConnectionTimedOut());
    //SE.addClient(client);
    try {
      client.connect().get(5000, TimeUnit.MILLISECONDS);
      fail();
    } catch(CancellationException e) {
      TestUtils.blockTillClockAdvances();
      assertTrue(client.hasConnectionTimedOut());
    }
  }
  
  @Test(expected=ConnectException.class)
  public void tcpConnectionRefused() throws Throwable {
    server.close();
    TCPClient client = SE.createTCPClient("localhost", port);
    assertTrue(!client.hasConnectionTimedOut());
    //final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    try {
      client.connect().get(5000, TimeUnit.MILLISECONDS);
      fail();
    } catch(ExecutionException e) {
      assertTrue(!client.hasConnectionTimedOut());
      assertEquals(0, SE.getClientCount());
      throw e.getCause();
    }
  }

  @Test
  public void closedServerStartTest() throws Exception {
    server.close();
    SE.startListening(server);
    SocketExecuter SE2 = new ThreadedSocketExecuter();
    SE2.start();
    TCPServer server2 = SE2.createTCPServer("localhost", port);
    SE.startListening(server2);
    server2.close();
    SE2.stopIfRunning();
    
  }
  
  @Test
  public void writerReaderBlockTest() throws Exception {
    TCPClient tc = SE.createTCPClient("localhost", port);
    tc.connect().get(5000, TimeUnit.MILLISECONDS);
    new TestCondition() {

      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
      
    }.blockTillTrue(5000);
    TCPClient sc = (TCPClient) serverFC.clients.get(0);
    while(tc.canRead()) {
      sc.write(LARGE_TEXT_BUFFER.duplicate()).get(1000, TimeUnit.MILLISECONDS);
      //System.out.println(tc.getReadBufferSize());
    }
    sc.setReader(null);
    while(sc.canRead()) {
      tc.write(LARGE_TEXT_BUFFER.duplicate()).get(1000, TimeUnit.MILLISECONDS);
      //System.out.println(sc.getReadBufferSize());
    }
    assertFalse(sc.canRead());
    assertFalse(tc.canRead());
    assertTrue(sc.getReadBufferSize() >= sc.getMaxBufferSize());
    assertTrue(tc.getReadBufferSize() >= tc.getMaxBufferSize());
    SE.setClientOperations(sc);
    SE.setClientOperations(tc);
    assertFalse(sc.canRead());
    assertFalse(tc.canRead());
    assertTrue(sc.getReadBufferSize() >= sc.getMaxBufferSize());
    assertTrue(tc.getReadBufferSize() >= tc.getMaxBufferSize());
  }
  
//  @Test
//  public void manualCreateTCPClient() throws Exception {
//    TCPClient tc = new TCPClient(SE, "localhost", port);
//    assertEquals(0, SE.getClientCount());
//    tc.connect().get(5000, TimeUnit.MILLISECONDS);
//    new TestCondition(){
//      @Override
//      public boolean get() {
//        return serverFC.map.size() == 1;
//      }
//    }.blockTillTrue(5000);
//    assertEquals(2, SE.getClientCount());
//    tc.close();
//    new TestCondition(){
//      @Override
//      public boolean get() {
//        return serverFC.map.size() == 0;
//      }
//    }.blockTillTrue(5000);
//    assertEquals(0, SE.getClientCount());
//  }
  
}
