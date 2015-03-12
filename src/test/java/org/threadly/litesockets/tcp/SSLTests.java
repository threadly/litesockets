package org.threadly.litesockets.tcp;

import static org.junit.Assert.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.tcp.ssl.SSLClient;
import org.threadly.litesockets.tcp.ssl.SSLServer;
import org.threadly.litesockets.tcp.ssl.SSLUtils;
import org.threadly.test.concurrent.TestCondition;

public class SSLTests {
  PriorityScheduler PS;
  int port = Utils.findTCPPort();
  final String GET = "hello";
  SocketExecuterInterface SE;
  TrustManager[] myTMs = new TrustManager [] {new SSLUtils.FullTrustManager() };
  KeyStore KS;
  KeyManagerFactory kmf;
  SSLContext sslCtx;
  FakeTCPServerClient serverFC;
  
  @Before
  public void start() throws Exception {
    PS = new PriorityScheduler(5, 5, 100000);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    port = Utils.findTCPPort();
    KS = KeyStore.getInstance(KeyStore.getDefaultType());
    String filename = ClassLoader.getSystemClassLoader().getResource("keystore.jks").getFile();
    FileInputStream ksf = new FileInputStream(filename);
    KS.load(ksf, "password".toCharArray());
    kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(KS, "password".toCharArray());
    sslCtx = SSLContext.getInstance("TLS");
    sslCtx.init(kmf.getKeyManagers(), myTMs, null);
    serverFC = new FakeTCPServerClient(SE);
  }
  
  @After
  public void stop() {
    for(Server s: serverFC.servers) {
      s.close();
    }
    
    for(Client c: serverFC.clients) {
      c.close();
    }
    SE.stop();
    PS.shutdownNow();
  }
  
  @Test
  public void simpleWriteTest() throws IOException {
    long start = System.currentTimeMillis();
    SSLServer server = new SSLServer("localhost", port, sslCtx);
    serverFC.addTCPServer(server);
    
    final SSLClient client = new SSLClient("localhost", port);
    System.out.println(System.currentTimeMillis()-start);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    SSLClient sclient = (SSLClient) serverFC.clients.get(0);
    
    serverFC.addTCPClient(client);

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 2;
      }
    }.blockTillTrue(5000);
    
    sclient.writeForce(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(client).remaining() > 2;
      }
    }.blockTillTrue(5000);
    
    String st = serverFC.map.get(client).getAsString(serverFC.map.get(client).remaining());
    assertEquals(TCPTests.SMALL_TEXT, st);
    
  }
  
  @Test
  public void largeWriteTest() throws IOException {
    
    SSLServer server = new SSLServer("localhost", port, sslCtx);
    serverFC.addTCPServer(server);
    
    final SSLClient client = new SSLClient("localhost", port);
    client.writeForce(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    client.writeForce(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    SSLClient sclient = (SSLClient) serverFC.clients.get(0);
    
    serverFC.addTCPClient(client);

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 2;
      }
    }.blockTillTrue(5000);
    
    sclient.writeForce(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    sclient.writeForce(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    sclient.writeForce(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(client).remaining() == TCPTests.LARGE_TEXT_BUFFER.remaining()*3;
      }
    }.blockTillTrue(5000);
    
    String st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    
  }
  
  
  @Test
  public void useTCPClient() throws IOException {
    long start = System.currentTimeMillis();
    SSLServer server = new SSLServer("localhost", port, sslCtx);
    serverFC.addTCPServer(server);
    
    final TCPClient tcp_client = new TCPClient("localhost", port);
    System.out.println(System.currentTimeMillis()-start);
    final SSLClient client = new SSLClient(tcp_client, this.sslCtx.createSSLEngine("localhost", port), true);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    SSLClient sclient = (SSLClient) serverFC.clients.get(0);
    
    serverFC.addTCPClient(client);

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 2;
      }
    }.blockTillTrue(5000);
    
    sclient.writeForce(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.get(client).remaining() > 2;
      }
    }.blockTillTrue(5000);
    
    String st = serverFC.map.get(client).getAsString(serverFC.map.get(client).remaining());
    assertEquals(TCPTests.SMALL_TEXT, st);
    
  }
  
  
  @Test(expected=IllegalStateException.class)
  public void useTCPClientPendingReads() throws IOException {
    TCPServer server = new TCPServer("localhost", port);
    serverFC.addTCPServer(server);
    
    final TCPClient tcp_client = new TCPClient("localhost", port);
    //serverFC.addTCPClient(tcp_client);
    SE.addClient(tcp_client);
    tcp_client.setReader(new Reader() {
      @Override
      public void onRead(Client client) {
        System.out.println("GOT READ");
        //We do nothing here
      }});
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    TCPClient sclient = (TCPClient) serverFC.clients.get(0);

    sclient.writeForce(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    
    new TestCondition(){
      @Override
      public boolean get() {
        return tcp_client.getReadBufferSize() > 0;
      }
    }.blockTillTrue(5000);
    
    final SSLClient client = new SSLClient(tcp_client, this.sslCtx.createSSLEngine("localhost", port), true);
    client.close();
  }
}
