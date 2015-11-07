package org.threadly.litesockets.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.TCPServer;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SSLUtils;
import org.threadly.test.concurrent.TestCondition;

public class SSLTests {
  PriorityScheduler PS;
  int port = Utils.findTCPPort();
  final String GET = "hello";
  SocketExecuter SE;
  TrustManager[] myTMs = new TrustManager [] {new SSLUtils.FullTrustManager() };
  KeyStore KS;
  KeyManagerFactory kmf;
  SSLContext sslCtx;
  FakeTCPServerClient serverFC;
  
  @Before
  public void start() throws Exception {
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    port = Utils.findTCPPort();
    KS = KeyStore.getInstance(KeyStore.getDefaultType());
    System.out.println(ClassLoader.getSystemClassLoader().getResource("keystore.jks"));
    String filename = ClassLoader.getSystemClassLoader().getResource("keystore.jks").getFile();
    FileInputStream ksf = new FileInputStream(filename);
    KS.load(ksf, "password".toCharArray());
    kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(KS, "password".toCharArray());
    //sslCtx = SSLContext.getInstance("TLS");
    sslCtx = SSLContext.getInstance("SSL");
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
    serverFC = new FakeTCPServerClient(SE);
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test(expected=IllegalStateException.class)
  public void badSSLStart() throws Exception {
    port = Utils.findTCPPort();
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);    
    serverFC.addTCPServer(server);
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    
    client.startSSL();
  }
  
  @Test
  public void simpleWriteTest() throws Exception {
    long start = System.currentTimeMillis();
    port = Utils.findTCPPort();
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);    
    serverFC.addTCPServer(server);
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    SSLEngine sslec = sslCtx.createSSLEngine("localhost", port);
    sslec.setUseClientMode(true);
    client.setSSLEngine(sslec);
    client.setReader(serverFC);
    client.connect().get(5000, TimeUnit.MILLISECONDS);

    client.startSSL().get(5000, TimeUnit.MILLISECONDS);;
    System.out.println(System.currentTimeMillis()-start);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    final TCPClient sclient = (TCPClient) serverFC.clients.get(0);
    serverFC.addTCPClient(client);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 2;
      }
    }.blockTillTrue(5000);
    new TestCondition(){
      @Override
      public boolean get() {
        return client.isEncrypted();
      }
    }.blockTillTrue(5000);
    assertTrue(client.isEncrypted());
    assertTrue(sclient.isEncrypted());
    System.out.println("Write");
    sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    System.out.println("Write Done");
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
  public void sslClientTimeout() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    TCPServer server = SE.createTCPServer("localhost", port);
    serverFC.addTCPServer(server);
    long start = System.currentTimeMillis();
    try {
      final TCPClient client = SE.createTCPClient("localhost", port);
      SSLEngine ssle = sslCtx.createSSLEngine("localhost", port);
      ssle.setUseClientMode(true);
      client.setSSLEngine(ssle);
      client.setConnectionTimeout(201);
      client.connect();
      client.startSSL().get(5000, TimeUnit.MILLISECONDS);
      fail();
    } catch(CancellationException e) {
      assertTrue(System.currentTimeMillis()-start >= 200);
    }
    server.close();
  }

  @Test
  public void largeWriteTest() throws Exception{
    
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setSSLHostName("localhost");
    server.setDoHandshake(true);
    serverFC.addTCPServer(server);
    
    final TCPClient client = SE.createTCPClient("localhost", port);
    SSLEngine sslec = sslCtx.createSSLEngine("localhost", port);
    sslec.setUseClientMode(true);
    client.setSSLEngine(sslec);
    client.connect();
    client.setReader(serverFC);
    client.startSSL();

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1 && client.isEncrypted();
      }
    }.blockTillTrue(5000);
    final TCPClient sclient = (TCPClient) serverFC.clients.get(0);

    serverFC.addTCPClient(client);
    
    for(int i=0; i<3; i++) {
      sclient.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    }
    
    new TestCondition(){
      @Override
      public boolean get() {
        /*
        System.out.println(serverFC.map.get(client).remaining()+":"+(TCPTests.LARGE_TEXT_BUFFER.remaining()*3));
        System.out.println("w:"+sclient.finishedHandshake.get()+":"+sclient.startedHandshake.get());
        System.out.println("w:"+sclient.ssle.getHandshakeStatus());
        System.out.println("r:"+client.ssle.getHandshakeStatus());
        System.out.println("r:"+client.getReadBufferSize());
        */
        if(serverFC.map.get(client) != null) {
          return serverFC.map.get(client).remaining() == TCPTests.LARGE_TEXT_BUFFER.remaining()*3;
        }
        return false;
      }
    }.blockTillTrue(5000);
    
    String st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
  }
    
//  @Test(expected=IllegalStateException.class)
//  public void useTCPClientPendingReads() throws IOException {
//    TCPServer server = SE.createTCPServer("localhost", port);
//    serverFC.addTCPServer(server);
//    
//    final TCPClient tcp_client = SE.createTCPClient("localhost", port);
//    //serverFC.addTCPClient(tcp_client);
//    SE.addClient(tcp_client);
//    tcp_client.setReader(new Reader() {
//      @Override
//      public void onRead(Client client) {
//        System.out.println("GOT READ");
//        //We do nothing here
//      }});
//    
//    new TestCondition(){
//      @Override
//      public boolean get() {
//        return serverFC.clients.size() == 1;
//      }
//    }.blockTillTrue(5000);
//    TCPClient sclient = (TCPClient) serverFC.clients.get(0);
//
//    sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
//    
//    new TestCondition(){
//      @Override
//      public boolean get() {
//        return tcp_client.getReadBufferSize() > 0;
//      }
//    }.blockTillTrue(5000);
//    
//    final SSLClient client = new SSLClient(tcp_client, this.sslCtx.createSSLEngine("localhost", port), true, true);
//    client.close();
//  }
  
//  @Test
//  public void loop() throws Exception {
//    for(int i=0; i<100; i++) {
//      this.doLateSSLhandshake();
//      stop();
//      start();
//    }
//  }
  
  @Test
  public void doLateSSLhandshake() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    TCPServer server = SE.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setSSLHostName("localhost");
    server.setDoHandshake(false);

    final AtomicReference<TCPClient> servers_client = new AtomicReference<TCPClient>();
    final AtomicReference<String> serversEncryptedString = new AtomicReference<String>();
    final AtomicReference<String> clientsEncryptedString = new AtomicReference<String>();
    
    server.setClientAcceptor(new ClientAcceptor() {
      @Override
      public void accept(Client c) {
        final TCPClient sslc = (TCPClient) c;
        servers_client.set(sslc);
        sslc.setReader(new Reader() {
          MergedByteBuffers mbb = new MergedByteBuffers();
          boolean didSSL = false;
          @Override
          public void onRead(Client client) {
            mbb.add(client.getRead());
            if(!didSSL && mbb.remaining() >= 6) {
              String tmp = mbb.getAsString(6);
              if(tmp.equals("DO_SSL")) {
                sslc.write(ByteBuffer.wrap("DO_SSL".getBytes()));
                System.out.println("DOSSL-Server");
                sslc.startSSL().addListener(new Runnable() {
                  @Override
                  public void run() {
                    didSSL = true;
                    System.out.println("DIDSSL-Server");
                  }});
              }
            } else {
              if(mbb.remaining() >= 19) {
                String tmp = mbb.getAsString(19);
                serversEncryptedString.set(tmp);
                client.write(ByteBuffer.wrap("THIS WAS ENCRYPTED!".getBytes()));
              }
            }
          }});
          //SE.addClient(sslc.getTCPClient());
      }});
    server.start();
    
    final TCPClient sslclient = SE.createTCPClient("localhost", port);
    SSLEngine sslec = sslCtx.createSSLEngine("localhost", port);
    sslec.setUseClientMode(true);
    sslclient.setSSLEngine(sslec);

    sslclient.setReader(new Reader() {
      MergedByteBuffers mbb = new MergedByteBuffers();
      boolean didSSL = false;
      @Override
      public void onRead(Client client) {
        mbb.add(client.getRead());
        if(!didSSL && mbb.remaining() >= 6) {
          String tmp = mbb.getAsString(6);
          if(tmp.equals("DO_SSL")) {
            System.out.println("DOSSL");
            sslclient.startSSL().addListener(new Runnable() {
              @Override
              public void run() {
                didSSL = true;
                sslclient.write(ByteBuffer.wrap("THIS WAS ENCRYPTED!".getBytes()));
                System.out.println("DIDSSL"); 
              }});

          }
        } else {
          if(mbb.remaining() >= 19) {
            String tmp = mbb.getAsString(19);
            clientsEncryptedString.set(tmp);
          }
        }
      }});
    System.out.println(sslclient);
    try {
      sslclient.connect().get(5000, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    System.out.println("WRITE!!");
    try {
      sslclient.write(ByteBuffer.wrap("DO_SSL".getBytes())).get(5000, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      System.out.println("WRITE ERROR!! "+sslclient.getWriteBufferSize());
      throw e;
    }
    System.out.println("WRITE DONE!!");
    
    new TestCondition(){
      @Override
      public boolean get() {
//        if(servers_client.get() != null) {
//          System.out.println(servers_client.get().getReadBufferSize());
//        }
        return clientsEncryptedString.get() != null && serversEncryptedString.get() != null;
      }
    }.blockTillTrue(5000);
    assertEquals(clientsEncryptedString.get(), serversEncryptedString.get());
  }
}
