package org.threadly.litesockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.tcp.FakeTCPServerClient;
import org.threadly.litesockets.tcp.TCPClient;
import org.threadly.litesockets.tcp.TCPServer;
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
  }
  
  @Test
  public void manyClientsTest() throws IOException, InterruptedException {
    final int clientCount = 50;
    TCPServer server = new TCPServer("localhost", port);
    final FakeTCPServerClient serverFC = new FakeTCPServerClient(SE);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);
    final ArrayList<TCPClient> clients = new  ArrayList<TCPClient>(clientCount);
    final ArrayList<TCPServer> servers = new  ArrayList<TCPServer>(clientCount);
    final ArrayList<FakeTCPServerClient> FCclients = new  ArrayList<FakeTCPServerClient>(clientCount);
    for(int i = 0; i<clientCount; i++) {
      PS.execute(new Runnable() {
        public void run() {
          TCPClient client;
          try {
            final int newport = Utils.findTCPPort();
            TCPServer server = new TCPServer("localhost", newport);
            FakeTCPServerClient clientFC = new FakeTCPServerClient(SE);
            server.setClientAcceptor(clientFC);
            SE.addServer(server);

            client = new TCPClient("localhost", port);
            client.setReader(clientFC);
            client.setCloser(clientFC);
            SE.addClient(client);

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
    for(TCPClient c: clients) {
      c.close();
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
    TCPServer server = new TCPServer("localhost", port);
    final FakeTCPServerClient serverFC = new FakeTCPServerClient(SE);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);
    
    SE.acceptSelector.close();
    
  }
  
  
  @Test
  public void SEStatsTest() throws IOException, InterruptedException {
    final int sendCount = 1000;
    int port = Utils.findTCPPort();
    final FakeTCPServerClient serverFC = new FakeTCPServerClient(SE);
    final TCPServer server = new TCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);
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
    for(int i=0; i<sendCount; i++) {
      client.writeForce(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    }

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(sclient).remaining() == TCPTests.SMALL_TEXT_BUFFER.remaining()*sendCount;
      }
    }.blockTillTrue(5000);
    for(int i=0; i<sendCount; i++) {
      sclient.writeForce(TCPTests.SMALL_TEXT_BUFFER.duplicate());
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
    }.blockTillTrue(1000);

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

}
