package org.threadly.litesockets.udp;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.tcp.Utils;
import org.threadly.test.concurrent.TestCondition;

public class UDPTest {
  PriorityScheduler PS;
  int port = Utils.findUDPPort();
  final String GET = "hello";
  SocketExecuterInterface SE;
  UDPServer server;
  FakeUDPServerClient serverFC;

  @Before
  public void start() throws IOException {
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    serverFC = new FakeUDPServerClient(SE);
    server = new UDPServer("127.0.0.1", port);
    server.setClientAcceptor(serverFC);
    SE.addServer(server);
  }
  
  @After
  public void stop() {
    server.close();
    SE.stop();
    PS.shutdownNow();
  }
  
  @Test
  public void simpleUDPTest() throws IOException {
    int newPort = Utils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = new UDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    c.writeForce(ByteBuffer.wrap(GET.getBytes()));
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
    SE.removeServer(newServer);
    c.close();
    newServer.close();
  }
  
  @Test
  public void manyUDPConnects() throws IOException, InterruptedException {
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    
    for(int i=0; i<10; i++) {
      int newPort = Utils.findUDPPort();
      UDPServer newServer = new UDPServer("localhost", newPort);
      newFC.AddUDPServer(newServer);
      UDPClient c = newServer.createUDPClient("127.0.0.1", port);
      newFC.accept(c);
      c.writeForce(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      c.writeBlocking(ByteBuffer.wrap(GET.getBytes()));
      Thread.sleep(10);
      c.writeTry(ByteBuffer.wrap(GET.getBytes()));
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
    int newPort = Utils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = new UDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    c.writeForce(ByteBuffer.wrap(GET.getBytes()));
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
    int newPort = Utils.findUDPPort();
    FakeUDPServerClient newFC = new FakeUDPServerClient(SE);
    UDPServer newServer = new UDPServer("localhost", newPort);
    newFC.AddUDPServer(newServer);
    UDPClient c = newServer.createUDPClient("127.0.0.1", port);
    newFC.accept(c);
    SE.addClient(c);
    assertEquals(0, SE.getClientCount());
    c.close();
    newServer.close();
  }

}
