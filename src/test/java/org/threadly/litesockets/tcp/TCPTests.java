package org.threadly.litesockets.tcp;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.test.concurrent.TestCondition;


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
  SocketExecuterInterface SE;
  TCPServer server;
  FakeTCPServerClient serverFC;
  
  @Before
  public void start() throws IOException {
    port = Utils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    serverFC = new FakeTCPServerClient(SE);
    server = new TCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);

  }
  
  @After
  public void stop() {    
    SE.stopIfRunning();
    PS.shutdown();
  }

  @Test
  public void simpleWriteTest() throws IOException, InterruptedException {
    final TCPClient client = new TCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.setReader(clientFC);
    client.setCloser(clientFC);
    SE.addClient(client);

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = (TCPClient) serverFC.map.keys().nextElement();
    client.writeForce(SMALL_TEXT_BUFFER.duplicate());

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(sclient).remaining() == SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.writeForce(SMALL_TEXT_BUFFER.duplicate());
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
    server = new TCPServer(socket);
    serverFC = new FakeTCPServerClient(SE);
    server.setClientAcceptor(serverFC);
    SE.addServer(server);
    TCPClient client = new TCPClient("localhost", port);
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
  public void clientBlockingWriter() throws IOException, InterruptedException {
    ByteBuffer bb = ByteBuffer.wrap("TEST111".getBytes());
    TCPClient client = new TCPClient("localhost", port);
    client.setMaxBufferSize(2);
    SE.addClient(client);
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
    c2.setMaxBufferSize(2);
    for(int i=0; i<100; i++) {
      c2.writeBlocking(bb.duplicate());
    }
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
    for(int i=0; i<1000; i++) {
      sb.append(text);
    }
    ByteBuffer bb = ByteBuffer.wrap(text.toString().getBytes());
    final TCPClient client = new TCPClient("localhost", port);
    client.setMaxBufferSize(2);
    client.setReader(new Reader() {

      @Override
      public void onRead(Client client) {

      }});
    SE.addClient(client);
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
    c2.setMaxBufferSize(60000);
    System.out.println(c2.writeTry(bb.duplicate()));
    new TestCondition(){
      @Override
      public boolean get() {
        return ! client.canRead();
      }
    }.blockTillTrue(5000, 100);
    MergedByteBuffers readBB = client.getRead();
    while(readBB != null) {
      readBB = client.getRead();
    }
    server.close();
  }
  
  @Test(expected=ClosedChannelException.class)
  public void clientBadSocket1() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.close();
    TCPClient client = new TCPClient(cs);
    server.close();
    client.close();
  }  
  
  @Test
  public void clientAddSocket() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = new TCPClient(cs);
    server.close();
    client.close();
  }
  
  @Test
  public void clientDoubleAdd() throws IOException, InterruptedException, ExecutionException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = new TCPClient(cs);
    SE.addClient(client);
    SE.addClient(client);
    SE.removeClient(client);
    SE.addClient(client);
    new TestCondition(){
      @Override
      public boolean get() {
        //System.out.println(SE.getClientCount());
        return SE.getClientCount() == 2;
      }
    }.blockTillTrue(5000);    
    
    client.close();
    server.close();
  }
  
  @Test
  public void clientAddToStopedSE() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = new TCPClient(cs);
    SE.addClient(client);
    SE.stop();
    assertEquals(0, SE.getClientCount());
    SE.addClient(client);
    SE.removeClient(client);
    SE.addClient(client);
    assertEquals(0, SE.getClientCount());
    client.close();
    server.close();
  }
  
  @Test
  public void clientAddClosed() throws IOException, InterruptedException {
    SocketChannel cs = SocketChannel.open(new InetSocketAddress("localhost", port));
    cs.configureBlocking(true);
    TCPClient client = new TCPClient(cs);
    client.close();
    SE.addClient(client);
    SE.stop();
    assertEquals(0, SE.getClientCount());
    SE.addClient(client);
    SE.removeClient(client);
    SE.addClient(client);
    assertEquals(0, SE.getClientCount());
    client.close();
    server.close();
  }
  
  @Test
  public void clientStartingWrite() throws IOException, InterruptedException {
    TCPClient client = new TCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.writeForce(SMALL_TEXT_BUFFER.duplicate());
    clientFC.addTCPClient(client);
    
    SE.addClient(client);
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
    final TCPClient client = new TCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    
    clientFC.addTCPClient(client);
    
    SE.addClient(client);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    
    final TCPClient c2 = (TCPClient)serverFC.clients.get(0);
    SE.removeClient(c2);
    SE.removeClient(client);
    
    for(int i=0; i<50000; i++) {
      client.writeForce(SMALL_TEXT_BUFFER.duplicate());  
    }
    SE.addClient(client);
    SE.addClient(c2);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(client.getWriteBufferSize());
        return serverFC.map.get(c2).remaining() == SMALL_TEXT_BUFFER.remaining()*50000 ;
      }
    }.blockTillTrue(5000, 100);
  }
  
  @Test
  public void bigWrite() throws IOException, InterruptedException {
    final TCPClient client = new TCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.writeForce(LARGE_TEXT_BUFFER.duplicate());
    clientFC.addTCPClient(client);
    client.writeForce(LARGE_TEXT_BUFFER.duplicate());
    client.writeForce(LARGE_TEXT_BUFFER.duplicate());
    client.writeForce(LARGE_TEXT_BUFFER.duplicate());
    SE.addClient(client);
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
    TCPClient client = new TCPClient("2.0.0.256", port, 1000);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    SE.addClient(client);
    client.connectionFuture.get();
  }
  
  @Test(expected=CancellationException.class)
  public void tcpTimeout() throws Throwable {
    TCPClient client = new TCPClient("2.0.0.2", port, 10);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    SE.addClient(client);
    try {
      client.connectionFuture.get();
    } catch(ExecutionException e) {
      throw e.getCause();
    }
  }
  
}
