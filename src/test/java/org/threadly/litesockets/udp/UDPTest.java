package org.threadly.litesockets.udp;

import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.UDPClient;
import org.threadly.litesockets.UDPServer;
import org.threadly.litesockets.utils.MergedByteBuffers;
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
  public void stop() {
    SE.stopListening(server);
    SE.stop();
    PS.shutdownNow();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
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
  public void changeBufferSize() throws IOException, InterruptedException, ExecutionException {
    int newPort = PortUtils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = SE.createUDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    assertEquals(0, c.getTimeout());
    c.connect().get();
    newFC.accept(c);
    c.setMaxBufferSize(2);
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
      newFC.AddUDPServer(newServer);
      UDPClient c = newServer.createUDPClient("127.0.0.1", port);
      newFC.accept(c);
      c.write(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      c.write(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      c.write(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
    }
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clientList.size() == 10;
      }
    }.blockTillTrue(5000);

    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = true;
        for(final UDPClient rc: serverFC.clientList) {
          if(serverFC.clients.get(rc).remaining() < GET.getBytes().length*3) {
            test = false;
          }
        }
        return test;
      }
    }.blockTillTrue(500);

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
  
  @Test
  public void stun() throws Exception {
    server = SE.createUDPServer("192.168.42.145", port);
    server.start();
    UDPClient c = server.createUDPClient("stun.l.google.com", 19302);
    ByteBuffer bb = ByteBuffer.allocate(20);
    bb.putShort((short)0x01);
    bb.putShort((short)0x0000);
    bb.putInt(0x2112A442);
    bb.putInt(0x21142);
    bb.putInt(0x21142);
    bb.putInt(0x21141);
    bb.flip();
    printBB(bb);
    c.setReader(new Reader() {

      @Override
      public void onRead(Client client) {
        MergedByteBuffers mbb = client.getRead();
        ByteBuffer bb = mbb.pull(mbb.remaining());
        printBB(bb);
        short response = bb.getShort();
        short bodyLen = bb.getShort();
        int magic = bb.getInt();
        byte[] ba = new byte[12];
        bb.get(ba);
        printBB(bb);
        System.out.println("response:"+response);
        System.out.println("bodyLen:"+bodyLen);
        System.out.println("magic:"+magic+":"+Integer.toHexString(magic));
        printBA(ba);
        printBB(bb);
        System.out.println(bb.remaining());
        short mapType = bb.getShort();
        short valLen = bb.getShort();
        System.out.println("valLen:"+valLen);
        printBB(bb);
        bb.getShort();
        short port = (short) (bb.getShort() ^ ((short) magic >> 16 ));
        int ip = bb.getInt() ^ magic;
        
        try {
          InetAddress ia = InetAddress.getByAddress(BigInteger.valueOf(ip).toByteArray());
          System.out.println(ia+":"+ip);
          System.out.println("port:"+port);
        } catch (UnknownHostException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        
      }});
    c.write(bb);
    Thread.sleep(1000);
  }
  
//  public byte[] xorBytes(byte[] ba1, byte[] ba2) {
//    
//  }

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
