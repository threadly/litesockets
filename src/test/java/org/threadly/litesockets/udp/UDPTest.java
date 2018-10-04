package org.threadly.litesockets.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.UDPClient;
import org.threadly.litesockets.UDPServer;
import org.threadly.litesockets.UDPServer.UDPFilterMode;
import org.threadly.litesockets.UDPServer.UDPReader;
import org.threadly.litesockets.utils.PortUtils;
import org.threadly.test.concurrent.TestCondition;

public class UDPTest {
  PriorityScheduler PS;
  int port = PortUtils.findUDPPort();
  final String GET = "hello";
  SocketExecuter SE;
  UDPServer server;
  FakeUDPServerClient serverFC;

  @Before
  public void start() throws IOException {
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    serverFC = new FakeUDPServerClient(SE);
    server = SE.createUDPServer("127.0.0.1", port);
    server.setClientAcceptor(serverFC);
    server.start();
  }
  
  @After
  public void stop()  throws InterruptedException {
    server.stop();
    SE.stopListening(server);
    SE.stop();
    PS.shutdownNow();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test
  public void simpleSetReader() throws IOException, InterruptedException, ExecutionException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    
    final SettableListenableFuture<Boolean> slf = new SettableListenableFuture<Boolean>();
    
    server.setUDPReader(new UDPReader() {
      @Override
      public boolean onUDPRead(ByteBuffer bb, InetSocketAddress isa) {
        slf.setResult(true);
        return false;
      }});
    
    c.write(ByteBuffer.wrap(GET.getBytes()));
    
    slf.get();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 0;
      }
    }.blockTillTrue(5000);
    
    server.setUDPReader(new UDPReader() {
      @Override
      public boolean onUDPRead(ByteBuffer bb, InetSocketAddress isa) {
        return true;
      }});
    
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    
    final UDPClient rc = serverFC.clientList.get(0);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }
  
  @Test
  public void simpleUDPTest() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }  
  
  @Test
  public void simpleUDPDirectWriteTest() throws IOException {
    final int newPort = PortUtils.findUDPPort();
    final FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    final UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    final UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    c.clientOptions().setDirectUdpWrites(true);
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    rc.clientOptions().setDirectUdpWrites(true);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }  
  
  
  @Test
  public void simpleUDPTestNativeBuffers() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    c.clientOptions().setNativeBuffers(true);
    c.clientOptions().setReducedReadAllocations(false);
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    rc.clientOptions().setNativeBuffers(true);
    rc.clientOptions().setReducedReadAllocations(false);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }
  
  @Test
  public void udpReadBufferAllocation() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    c.clientOptions().setReducedReadAllocations(false);
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    rc.clientOptions().setReducedReadAllocations(false);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }
  
  @Test
  public void udpRecvSocketSize() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    assertFalse(c.clientOptions().setSocketRecvBuffer(1));
    assertTrue(c.clientOptions().setSocketRecvBuffer(4096));
    assertEquals(4096, c.clientOptions().getSocketRecvBuffer());
    
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    rc.clientOptions().setReducedReadAllocations(false);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }
  
  @Test
  public void udpSendSocketSize() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    assertFalse(c.clientOptions().setSocketSendBuffer(1));
    assertTrue(c.clientOptions().setSocketSendBuffer(4096));
    assertEquals(4096, c.clientOptions().getSocketSendBuffer());
    
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    rc.clientOptions().setReducedReadAllocations(false);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }
 
  @Test
  public void udpWhiteListTest() throws IOException, InterruptedException {
    int whitePort = PortUtils.findUDPPort();
    FakeUDPServerClient whiteFC = new FakeUDPServerClient(SE);
    UDPServer whiteServer = SE.createUDPServer("127.0.0.1", whitePort);
    whiteServer.start();
    whiteFC.AddUDPServer(whiteServer);
    UDPClient whiteC = whiteServer.createUDPClient("127.0.0.1", port);
    whiteFC.accept(whiteC);
    
    
    int blackPort = PortUtils.findUDPPort();
    FakeUDPServerClient blackFC = new FakeUDPServerClient(SE);
    UDPServer blackServer = SE.createUDPServer("127.0.0.1", blackPort);
    blackServer.start();
    blackFC.AddUDPServer(blackServer);
    UDPClient blackC = blackServer.createUDPClient("127.0.0.1", port);
    blackFC.accept(blackC);
    
    server.setFilterMode(UDPFilterMode.WhiteList);
    server.filterHost(new InetSocketAddress("127.0.0.1", whitePort));

    for(int i=0; i<100; i++) {
      blackC.write(ByteBuffer.wrap(GET.getBytes()));
      assertEquals(0, serverFC.clientList.size());
    }
    assertEquals(0, serverFC.clientList.size());
    
    whiteC.write(ByteBuffer.wrap(GET.getBytes()));
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(500);
    
    final UDPClient rc = serverFC.clientList.get(0);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    
    System.out.println(serverFC.clients.get(rc).remaining());
    
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    blackServer.close();
    whiteServer.close();
  }
  
  @Test
  public void udpBlackListTest() throws IOException, InterruptedException {
    
    int whitePort = PortUtils.findUDPPort();
    FakeUDPServerClient whiteFC = new FakeUDPServerClient(SE);
    UDPServer whiteServer = SE.createUDPServer("127.0.0.1", whitePort);
    whiteServer.start();
    whiteFC.AddUDPServer(whiteServer);
    UDPClient whiteC = whiteServer.createUDPClient("127.0.0.1", port);
    whiteFC.accept(whiteC);
    
    
    int blackPort = PortUtils.findUDPPort();
    FakeUDPServerClient blackFC = new FakeUDPServerClient(SE);
    UDPServer blackServer = SE.createUDPServer("127.0.0.1", blackPort);
    blackServer.start();
    blackFC.AddUDPServer(blackServer);
    UDPClient blackC = blackServer.createUDPClient("127.0.0.1", port);
    blackFC.accept(blackC);
    
    server.setFilterMode(UDPFilterMode.BlackList);
    server.filterHost(new InetSocketAddress("127.0.0.1", whitePort));
    
    for(int i=0; i<100; i++) {
      whiteC.write(ByteBuffer.wrap(GET.getBytes()));
      assertEquals(0, serverFC.clientList.size());
    }
    
    blackC.write(ByteBuffer.wrap(GET.getBytes()));
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(500);
    
    final UDPClient rc = serverFC.clientList.get(0);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    
    System.out.println(serverFC.clients.get(rc).remaining());
    
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    blackServer.close();
    whiteServer.close();
  }
  
  
  @Test
  public void changeBufferSize() throws IOException, InterruptedException, ExecutionException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    assertEquals(0, c.getTimeout());
    c.connect().get();
    newFC.accept(c);
    c.clientOptions().setMaxClientReadBuffer(2);
    assertEquals(c.getClientsSocketExecuter(), SE);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.get(rc).remaining() > 0;
      }
    }.blockTillTrue(5000);
    System.out.println(serverFC.clients.get(rc).remaining());
    assertEquals(GET, serverFC.clients.get(rc).getAsString(serverFC.clients.get(rc).remaining()));
    newServer.close();
    c.close();
    newServer.close();
  }
  
  @Test
  public void manyUDPConnects() throws IOException, InterruptedException {
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    
    for(int i=0; i<10; i++) {
      int newPort = PortUtils.findUDPPort();
      UDPServer newServer = SE.createUDPServer("localhost", newPort);
      newServer.start();
      newFC.AddUDPServer(newServer);
      UDPClient c = newServer.createUDPClient("127.0.0.1", port);
      newFC.accept(c);
      c.write(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      c.write(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      c.write(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      System.out.println("Used Memory:"
          + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
    }
    
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(serverFC.clientList.size()+":"+serverFC);
        System.out.println("Used Memory:"
            + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
        return serverFC.clientList.size() == 10;
      }
    }.blockTillTrue(50000, 100);

    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = true;
        for(final UDPClient rc: serverFC.clientList) {
          if(serverFC.clients.get(rc).remaining() < GET.getBytes().length*3) {
            test = false;
          }
        }
        System.gc();
        System.out.println("Used Memory:"
            + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
        return test;
      }
    }.blockTillTrue(50000);

    for(int i=0; i<10; i++) {
      assertEquals(GET, serverFC.clients.get(serverFC.clientList.get(i)).getAsString(GET.getBytes().length));
      assertEquals(GET, serverFC.clients.get(serverFC.clientList.get(i)).getAsString(GET.getBytes().length));
      assertEquals(GET, serverFC.clients.get(serverFC.clientList.get(i)).getAsString(GET.getBytes().length));
    }
    HashSet<UDPServer> x =  new HashSet<UDPServer>(newFC.servers);
    for(UDPServer s: x) {
      s.close();
    }
  }
  
  @Test
  public void checkClients() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    c.write(ByteBuffer.wrap(GET.getBytes()));
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 1;
      }
    }.blockTillTrue(5000);
    final UDPClient rc = serverFC.clientList.get(0);
    UDPClient newc = server.createUDPClient("127.0.0.1", newPort);
    assertEquals(rc, newc);
    assertFalse(c.equals(newc));
    c.close();
    newServer.close();
  }
  
  
  @Test
  public void tryAddClient() throws IOException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    assertEquals(0, SE.getClientCount());
    c.close();
    newServer.close();
  }

  public void printBA(byte[] ba) {
    printBB(ByteBuffer.wrap(ba));
  }
  
  public void printBB(ByteBuffer bb) {
    ByteBuffer bb2 = bb.duplicate();
    
    byte[] ba = new byte[bb2.remaining()];
    bb2.get(ba);
    StringBuilder sb = new StringBuilder(ba.length * 2);
    for(byte b: ba) {
      sb.append(String.format("%02x", b & 0xff));
    }
    System.out.println(sb.toString());
  }
}
