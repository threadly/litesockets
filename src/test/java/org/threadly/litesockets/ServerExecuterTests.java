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
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.test.concurrent.TestCondition;
import org.threadly.util.Clock;

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
    }.blockTillTrue(10000);
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
    }.blockTillTrue(10000);    
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

    int endSize = TCPTests.SMALL_TEXT_BUFFER.remaining()*sendCount;
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    serverFC.addTCPClient(client);

    long start = Clock.accurateForwardProgressingMillis();
    while(serverFC.clients.size() != 2 || Clock.accurateForwardProgressingMillis() - start > 5000) {
      Thread.sleep(10);
    }
    assertEquals(2, serverFC.clients.size());

    final TCPClient sclient = (TCPClient) serverFC.clients.get(1);

    for(int i=0; i<sendCount; i++) {
      client.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
      sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    }
    
    start = Clock.accurateForwardProgressingMillis();
    MergedByteBuffers mbb0 = serverFC.map.get(client);
    MergedByteBuffers mbb1 = serverFC.map.get(sclient);
    while(mbb0.remaining() < endSize  ||
        mbb1.remaining() < endSize ||
        Clock.accurateForwardProgressingMillis() - start > 5000) {
      Thread.sleep(10);
    }

    assertEquals(endSize, serverFC.map.get(serverFC.clients.get(0)).remaining());
    assertEquals(endSize, serverFC.map.get(serverFC.clients.get(1)).remaining());

    client.close();
    server.close();

    assertEquals(endSize*2, SE.getStats().getTotalWrite());
    assertEquals(endSize*2, SE.getStats().getTotalRead());
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
