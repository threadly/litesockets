package org.threadly.litesockets;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.tcp.FakeTCPServerClient;
import org.threadly.litesockets.tcp.TCPTests;
import org.threadly.litesockets.tcp.Utils;
import org.threadly.test.concurrent.TestCondition;

public class ServerExecuterTests {
  PriorityScheduler PS;
  int port = Utils.findTCPPort();
  ThreadedSocketExecuter SE;
  
  @Before
  public void start() {
    port = Utils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter();
    SE.start();
  }
  
  @After
  public void stop() {
    SE.stopIfRunning();
    PS.shutdownNow();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  

  @Test(expected=IllegalStateException.class)
  public void clientFromStoppedSE() throws IOException, InterruptedException {
    SE.stop();
    SE.createTCPClient("localhost", port);
  }
  
  @Test
  public void manyClientsTest() throws IOException, InterruptedException {
    final int clientCount = 50;
    TCPServer server = SE.createTCPServer("localhost", port);
    final FakeTCPServerClient serverFC = new FakeTCPServerClient(SE);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
    final ArrayList<TCPClient> clients = new  ArrayList<TCPClient>(clientCount);
    final ArrayList<TCPServer> servers = new  ArrayList<TCPServer>(clientCount);
    final ArrayList<FakeTCPServerClient> FCclients = new  ArrayList<FakeTCPServerClient>(clientCount);
    for(int i = 0; i<clientCount; i++) {
      PS.execute(new Runnable() {
        public void run() {
          TCPClient client;
          try {
            final int newport = Utils.findTCPPort();
            TCPServer server = SE.createTCPServer("localhost", newport);
            FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
            server.setClientAcceptor(clientFC);
            server.start();

            client = SE.createTCPClient("localhost", port);
            client.setReader(clientFC);
            client.addCloseListener(clientFC);
            client.connect();

            synchronized(clients) {
              servers.add(server);
              clients.add(client);
              FCclients.add(clientFC);
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      });
    }
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == clientCount;
      }
    }.blockTillTrue(20 * 1000, 100);
    assertEquals(clientCount*2, SE.getClientCount());
    assertEquals(clientCount+1, SE.getServerCount());
    synchronized(clients) {
      for(TCPClient c: clients) {
        c.close();
      }
    }
    new TestCondition(){
      @Override
      public boolean get() {
        //System.out.println("SE Clients:"+SE.getClientCount()+":"+SE.readSelector.keys().size());
        return serverFC.map.size() == 0;
      }
    }.blockTillTrue(20 * 1000, 100);    
  }
  
  @Test
  public void closeAcceptor() throws IOException {
    TCPServer server = SE.createTCPServer("localhost", port);
    final FakeTCPServerClient serverFC = new FakeTCPServerClient(SE);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
    SE.acceptScheduler.equals(new Runnable() {
      @Override
      public void run() {
        try {
          SE.acceptSelector.close();
        } catch (IOException e) {
        }        
      }});

    
  }
  
  @Test
  public void SEStatsTest() throws IOException, InterruptedException {
    final int sendCount = 1000;
    int port = Utils.findTCPPort();
    final FakeTCPServerClient serverFC = new FakeTCPServerClient(SE);
    final TCPServer server = SE.createTCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();

    final TCPClient client = SE.createTCPClient("localhost", port);
    final FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
    client.setReader(clientFC);
    client.addCloseListener(clientFC);
    client.connect();

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);

    final TCPClient sclient = (TCPClient) serverFC.clients.get(0);
    
    for(int i=0; i<sendCount; i++) {
      client.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    }

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(sclient).remaining() == TCPTests.SMALL_TEXT_BUFFER.remaining()*sendCount;
      }
    }.blockTillTrue(5000);
    
    
    for(int i=0; i<sendCount; i++) {
      sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    }
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test =clientFC.map.get(client).remaining() == TCPTests.SMALL_TEXT_BUFFER.remaining()*sendCount;
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000, 100);

    client.close();
    new TestCondition(){
      @Override
      public boolean get() {
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(5000);
    server.close();
    assertEquals(sendCount*2*TCPTests.SMALL_TEXT_BUFFER.remaining(), SE.getStats().getTotalWrite());
    assertEquals(sendCount*2*TCPTests.SMALL_TEXT_BUFFER.remaining(), SE.getStats().getTotalRead());
    System.out.println(SE.getStats().getTotalWrite());
    System.out.println(SE.getStats().getTotalRead());
  }
  
  @Test
  public void serverSizeTest() throws IOException {
    Server lserver = SE.createTCPServer("localhost", Utils.findTCPPort());
    lserver.start();
    lserver.close();
    System.out.println(SE.getServerCount());
  }

}
