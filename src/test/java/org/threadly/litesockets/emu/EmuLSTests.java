package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;

import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.TCPServer;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.tcp.FakeTCPServerClient;
import org.threadly.litesockets.tcp.TCPTests;
import org.threadly.test.concurrent.TestCondition;

public class EmuLSTests {
  static String testString = "";
  static {
    StringBuilder sb = new StringBuilder();
    for(int i=0; i<1000; i++) {
      sb.append("1234567890");
    }
    testString = sb.toString();
  }
  InetSocketAddress localISA = new InetSocketAddress("127.0.0.1", 13723);
  
  @Test
  public void simpleWriteTest() throws IOException, InterruptedException {
    Selector selector = EmuSelector.open();
    SelectorProvider sp = selector.provider();
    FakeTCPServerClient serverFC = new FakeTCPServerClient();
    PriorityScheduler PS = new PriorityScheduler(5);
    ThreadedSocketExecuter TSE = new ThreadedSocketExecuter(PS, 10, 10, sp);
    TSE.start();
    TCPServer server = TSE.createTCPServer(localISA.getHostName(), localISA.getPort());
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
    final TCPClient client = TSE.createTCPClient(localISA.getHostName(), localISA.getPort());
    final FakeTCPServerClient clientFC = new FakeTCPServerClient();
    clientFC.addTCPClient(client);
    client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getNumberOfClients() == 1;
      }
    }.blockTillTrue(500, 5000);

    final TCPClient sclient = serverFC.getClientAt(0);
    client.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.getClientsBuffer(sclient).remaining() == TCPTests.SMALL_TEXT_BUFFER.remaining();
      }
    }.blockTillTrue(5000);

    sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        boolean test = false;
        try {
          test = clientFC.getClientsBuffer(client).remaining() == TCPTests.SMALL_TEXT_BUFFER.remaining();
        } catch(Exception e) {

        }
        return test;
      }
    }.blockTillTrue(1000);

    client.close();
    TSE.setClientOperations(sclient);
    new TestCondition(){
      @Override
      public boolean get() {
        System.out.println(sclient.isClosed()+":"+client.isClosed());
        TSE.setClientOperations(client);
        return sclient.isClosed() && client.isClosed();
      }
    }.blockTillTrue(10000, 100);
    server.close();
  }
}
