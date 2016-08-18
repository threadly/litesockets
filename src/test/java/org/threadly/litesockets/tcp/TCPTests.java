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
import org.threadly.litesockets.utils.PortUtils;
import org.threadly.test.concurrent.TestCondition;


public class TCPTests {
  private static final String OS = System.getProperty("os.name").toLowerCase();
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
  int port;
  final String GET = "hello";
  SocketExecuter SE;
  TCPServer server;
  FakeTCPServerClient serverFC;
  
  @Before
  public void start() throws IOException {
    port = PortUtils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    serverFC = new FakeTCPServerClient();
    server = SE.createTCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
  }
  
  @After
  public void stop() throws Exception{
    serverFC = null;
    Runtime.getRuntime().gc();
    Runtime.getRuntime().gc();
    server.stop();
    SE.stopIfRunning();
    PS.shutdownNow();
    server.stop();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
    
  }

  @Test(expected=IllegalStateException.class)
  public void writeClosedSocket() throws Throwable {
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.connect().get(5000, TimeUnit.MILLISECONDS);
    client.close();
    try {
      client.write(SMALL_TEXT_BUFFER.duplicate()).get();
    } catch(Exception e) {
      throw e.getCause();
    }
  }
  
  
  @Test
  public void setClientOptions() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    assertTrue(client.clientOptions().setTcpNoDelay(true));
    assertTrue(client.clientOptions().getTcpNoDelay());
    assertTrue(client.clientOptions().setTcpNoDelay(false));
    assertFalse(client.clientOptions().getTcpNoDelay());
    
    assertFalse(client.clientOptions().setSocketSendBuffer(0));
    assertTrue(client.clientOptions().setSocketSendBuffer(16384));
    assertEquals(16384, client.clientOptions().getSocketSendBuffer());

    assertFalse(client.clientOptions().setSocketRecvBuffer(0));
    assertTrue(client.clientOptions().setSocketRecvBuffer(16384));
    assertEquals(16384, client.clientOptions().getSocketRecvBuffer());

    assertFalse(client.clientOptions().setUdpFrameSize(1000));
    assertEquals(-1, client.clientOptions().getUdpFrameSize());
    
    assertFalse(client.isEncrypted());
    if(!OS.contains("win")) {
      assertFalse(client.clientOptions().setSocketSendBuffer(1));
      assertFalse(client.clientOptions().setSocketRecvBuffer(1));
    }
    
    client.close();
    
    assertEquals(-1, client.clientOptions().getSocketRecvBuffer());
    assertEquals(-1, client.clientOptions().getSocketSendBuffer());
    assertFalse(client.clientOptions().setSocketSendBuffer(16384));
    assertFalse(client.clientOptions().setSocketRecvBuffer(16384));
    assertFalse(client.clientOptions().setTcpNoDelay(true));
    assertFalse(client.clientOptions().getTcpNoDelay());
    
    server.close();
  }
  
  @Test
  public void noPreReaderTest() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = serverFC.getClientAt(0);
    sclient.setReader(null);
    client.write(SMALL_TEXT_BUFFER.duplicate()).get(5, TimeUnit.SECONDS);
    sclient.setReader(serverFC);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test = clientFC.getClientsBuffer(client).remaining() == SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    SE.setClientOperations(sclient);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(sclient.isClosed()+":"+client.isClosed());
        SE.setClientOperations(client);
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(10000, 100);
    server.close();

  }
  
  @Test
  public void simpleWriteTest() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = serverFC.getClientAt(0);
    client.write(SMALL_TEXT_BUFFER.duplicate());
    //System.out.println("1");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test = clientFC.getClientsBuffer(client).remaining() == SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    SE.setClientOperations(sclient);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(sclient.isClosed()+":"+client.isClosed());
        SE.setClientOperations(client);
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(10000, 100);
    server.close();

  }
  
  @Test
  public void simpleWriteTestPerReadAllocation() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.clientOptions().setReducedReadAllocations(false);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = serverFC.getClientAt(0);
    sclient.clientOptions().setReducedReadAllocations(false);
    client.write(SMALL_TEXT_BUFFER.duplicate());
    //System.out.println("1");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test = clientFC.getClientsBuffer(client).remaining() == SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    SE.setClientOperations(sclient);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(sclient.isClosed()+":"+client.isClosed());
        SE.setClientOperations(client);
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(10000, 100);
    server.close();
  }
  
  @Test
  public void simpleWriteTestNativeBuffers() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.clientOptions().setNativeBuffers(true);
    client.clientOptions().setReducedReadAllocations(false);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = serverFC.getClientAt(0);
    sclient.clientOptions().setNativeBuffers(true);
    sclient.clientOptions().setReducedReadAllocations(false);
    client.write(SMALL_TEXT_BUFFER.duplicate());
    //System.out.println("1");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test = clientFC.getClientsBuffer(client).remaining() == SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    SE.setClientOperations(sclient);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(sclient.isClosed()+":"+client.isClosed());
        SE.setClientOperations(client);
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(10000, 100);
    server.close();
  }

  @Test
  public void simpleWriteTestNative() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.clientOptions().setNativeBuffers(true);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = serverFC.getClientAt(0);
    sclient.clientOptions().setNativeBuffers(true);
    client.write(SMALL_TEXT_BUFFER.duplicate());
    //System.out.println("1");
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test =clientFC.getClientsBuffer(client).remaining() == SMALL_TEXT_BUFFER.remaining();
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
    serverFC = new FakeTCPServerClient();
    server.setClientAcceptor(serverFC);
    server.start();
    TCPClient client = SE.createTCPClient("localhost", port);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000, 100);
    client.close();
    server.close();
  }
  
  @Test
  public void clientBlockingWriter() throws Exception {
    final ByteBuffer bb = ByteBuffer.wrap("TEST111".getBytes());
    final TCPClient client = SE.createTCPClient("localhost", port);
    client.clientOptions().setMaxClientReadBuffer(2);
    serverFC.addTCPClient(client);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 2;
      }
    }.blockTillTrue(5000);
    TCPClient c2 = serverFC.getClientAt(1);
    c2.clientOptions().setMaxClientReadBuffer(2);
    ArrayList<ListenableFuture<?>> lfl = new ArrayList<ListenableFuture<?>>(); 
    for(int i=0; i<100; i++) {
      lfl.add(c2.write(bb.duplicate()));
    }
    FutureUtils.makeCompleteFuture(lfl).get(5000, TimeUnit.MILLISECONDS);
    System.out.println(client.canRead());
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(client.canRead());
        System.out.println(serverFC.getClientsBuffer(client).remaining());
        return serverFC.getClientsBuffer(client).remaining() == bb.remaining()*100;
      }
    }.blockTillTrue(5000, 1000);
    c2.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 0;
      }
    }.blockTillTrue(5000);
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
    client.clientOptions().setMaxClientReadBuffer(2);
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
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000, 100);
    System.out.println("4");
    final TCPClient c2 = serverFC.getClientAt(0);
    c2.clientOptions().setMaxClientReadBuffer(60000);
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
    }.blockTillTrue(5000);
    client.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return SE.getClientCount() == 0;
      }
    }.blockTillTrue(5000);
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
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    client.write(SMALL_TEXT_BUFFER.duplicate());
    clientFC.addTCPClient(client);
    client.connect();

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);
    
    final TCPClient cf = serverFC.getClientAt(0);

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(cf).remaining() > 0 ;
      }
    }.blockTillTrue(5000);
    assertEquals(serverFC.getClientsBuffer(cf).remaining(), SMALL_TEXT_BUFFER.remaining());
    assertEquals(serverFC.getClientsBuffer(cf).getAsString(serverFC.getClientsBuffer(cf).remaining()), SMALL_TEXT);
  }
  
  
  @Test
  public void clientLateReadStart() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    for(int i=0; i<50000; i++) {
      client.write(SMALL_TEXT_BUFFER.duplicate());  
    }
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);
    
    final TCPClient c2 = serverFC.getClientAt(0);
    
    client.connect();
    c2.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        //System.out.println(client.getWriteBufferSize());
        return serverFC.getClientsBuffer(c2).remaining() == SMALL_TEXT_BUFFER.remaining()*50000 ;
      }
    }.blockTillTrue(10000);
  }
  
  @Test
  public void bigWrite() throws IOException, InterruptedException {
    final TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    client.write(LARGE_TEXT_BUFFER.duplicate());
    clientFC.addTCPClient(client);
    client.write(LARGE_TEXT_BUFFER.duplicate());
    client.write(LARGE_TEXT_BUFFER.duplicate());
    client.write(LARGE_TEXT_BUFFER.duplicate());
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(5000);
    
    final TCPClient cf = serverFC.getClientAt(0);
    new TestCondition(){
      @Override
      public boolean get() {
        //System.out.println(serverFC.map.get(cf).remaining()+":"+(LARGE_TEXT_BUFFER.remaining()*4));
        
        return serverFC.getClientsBuffer(cf).remaining() == LARGE_TEXT_BUFFER.remaining()*4 ;
      }
    }.blockTillTrue(5000);
    assertEquals(serverFC.getClientsBuffer(cf).remaining(), LARGE_TEXT_BUFFER.remaining()*4);
    assertEquals(serverFC.getClientsBuffer(cf).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    assertEquals(serverFC.getClientsBuffer(cf).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    assertEquals(serverFC.getClientsBuffer(cf).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
    assertEquals(serverFC.getClientsBuffer(cf).getAsString(LARGE_TEXT_BUFFER.remaining()), LARGE_TEXT);
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
    client.setConnectionTimeout(1);
    assertTrue(!client.hasConnectionTimedOut());
    ListenableFuture<?> lf = client.connect();
    Thread.sleep(10);
    assertTrue(client.hasConnectionTimedOut());
    System.out.println(lf.isCancelled());
    System.out.println(lf.isDone());
    while(!lf.isCancelled() || !lf.isDone()) {
      Thread.sleep(1);
    }
    System.out.println(lf.isCancelled());
    System.out.println(lf.isDone());
    //assertTrue(lf.isCancelled());
    
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
        return serverFC.getNumberOfClients() == 1;
      }
      
    }.blockTillTrue(5000);
    TCPClient sc = serverFC.getClientAt(0);
    while(tc.canRead()) {
      sc.write(LARGE_TEXT_BUFFER.duplicate());
      Thread.sleep(10);
    }
    sc.setReader(null);
    while(sc.canRead()) {
    	tc.write(LARGE_TEXT_BUFFER.duplicate());
    	Thread.sleep(10);
    }
    assertFalse(sc.canRead());
    assertFalse(tc.canRead());
    assertTrue(sc.getReadBufferSize() >= sc.clientOptions().getMaxClientReadBuffer());
    assertTrue(tc.getReadBufferSize() >= sc.clientOptions().getMaxClientReadBuffer());
    SE.setClientOperations(sc);
    SE.setClientOperations(tc);
    assertFalse(sc.canRead());
    assertFalse(tc.canRead());
    assertTrue(sc.getReadBufferSize() >= sc.clientOptions().getMaxClientReadBuffer());
    assertTrue(tc.getReadBufferSize() >= sc.clientOptions().getMaxClientReadBuffer());
  }
  
  @Test
  public void manyClientsMemoryTest() throws Exception {
    ArrayList<ListenableFuture<?>> lfl = new ArrayList<ListenableFuture<?>>();
    for(int i=0; i<100; i++) {
      TCPClient tc = SE.createTCPClient("127.0.0.1", port);
      serverFC.addTCPClient(tc);
      lfl.add(tc.connect());
    }
    FutureUtils.makeCompleteFuture(lfl).get(5000, TimeUnit.MILLISECONDS);
    new TestCondition(){
      @Override
      public boolean get() {
        return 200 == SE.getClientCount() ;
      }
    }.blockTillTrue(5000);
    assertEquals(200, SE.getClientCount());
    for(TCPClient tc: serverFC.getAllClients()) {
      tc.write(SMALL_TEXT_BUFFER.duplicate());
    }
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
    for(TCPClient tc: serverFC.getAllClients()) {
      MergedByteBuffers mbb = serverFC.getClientsBuffer(tc);
      mbb.discard(mbb.remaining());
    }
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
}
