package org.threadly.litesockets.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.security.KeyStore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

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
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.utils.MergedByteBuffers;
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
  }
  
  @Test
  public void simpleWriteTest() throws Exception {
    long start = System.currentTimeMillis();
    port = Utils.findTCPPort();
    SSLServer server = new SSLServer(SE.createTCPServer("localhost", port), sslCtx, true);
    serverFC.addTCPServer(server);
    
    final SSLClient client = new SSLClient(SE.createTCPClient("localhost", port));
    client.connect().get(5000, TimeUnit.MILLISECONDS);
    client.doHandShake();
    System.out.println(System.currentTimeMillis()-start);
    
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    final SSLClient sclient = (SSLClient) serverFC.clients.get(0);
    
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
    }.blockTillTrue(5000, 10);
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
  public void sslClientTimeout() throws IOException, InterruptedException, ExecutionException {
    TCPServer server = SE.createTCPServer("localhost", port);
    serverFC.addTCPServer(server);
    long start = System.currentTimeMillis();
    try {
      final SSLClient client = new SSLClient(SE.createTCPClient("localhost", port), this.sslCtx.createSSLEngine("localhost", port), false, true);
      client.setConnectionTimeout(201);
      client.connect();
      client.doHandShake().get();
      fail();
    } catch(CancellationException e) {
      e.printStackTrace();
      assertTrue(System.currentTimeMillis()-start >= 200);
      System.out.println(System.currentTimeMillis()-start );
    }
    server.close();
  }

  @Test
  public void largeWriteTest() throws Exception{
    
    SSLServer server = new SSLServer(SE.createTCPServer("localhost", port), sslCtx, true);
    serverFC.addTCPServer(server);
    
    final SSLClient client = new SSLClient(SE.createTCPClient("localhost", port));
    client.connect();
    
    client.doHandShake().get(5000, TimeUnit.MILLISECONDS);
    client.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    client.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    final SSLClient sclient = (SSLClient) serverFC.clients.get(0);

    serverFC.addTCPClient(client);

    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 2;
      }
    }.blockTillTrue(5000);
    
    sclient.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    sclient.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    sclient.write(TCPTests.LARGE_TEXT_BUFFER.duplicate());
    //System.out.println("w:"+sclient.getWriteBufferSize());
    //System.out.println(":"+TCPTests.LARGE_TEXT_BUFFER.remaining());
    
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
    }.blockTillTrue(5000, 500);
    
    String st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    st = serverFC.map.get(client).getAsString(TCPTests.LARGE_TEXT_BUFFER.remaining());
    assertEquals(TCPTests.LARGE_TEXT, st);
    
  }
  
  
  //@Test
  public void loop() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    for(int i=0; i<100; i++) {
      useTCPClient();
      serverFC = new FakeTCPServerClient(SE);
    }
  }
  
  @Test
  public void useTCPClient() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    port = Utils.findTCPPort();
    ServerSocketChannel socket = ServerSocketChannel.open();
    socket.socket().bind(new InetSocketAddress("localhost", port), 100);
    socket.configureBlocking(false);
    SSLServer server = new SSLServer(SE.createTCPServer(socket), sslCtx, true);
    serverFC.addTCPServer(server);
    //System.out.println(serverFC);
    
    final TCPClient tcp_client = SE.createTCPClient("localhost", port);
    tcp_client.connect();
    new TestCondition(){
      @Override
      public boolean get() {
        return tcp_client.connect().isDone();
      }
    }.blockTillTrue(5000);
    //SE.removeClient(tcp_client);
    final SSLClient client = new SSLClient(tcp_client, this.sslCtx.createSSLEngine("localhost", port), false, true);
    //System.out.println(serverFC);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    final SSLClient sclient = (SSLClient) serverFC.clients.get(0);


    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.clients.size() == 1;
      }
    }.blockTillTrue(5000);
    serverFC.addTCPClient(client);
    client.doHandShake().get(5000, TimeUnit.MILLISECONDS);
    sclient.write(TCPTests.SMALL_TEXT_BUFFER.duplicate());
    new TestCondition(){
      @Override
      public boolean get() {
        /*
        System.out.println(serverFC);
        System.out.println(serverFC.map.get(client).remaining());
        System.out.println(serverFC.clients.size());
        System.out.println(client.isEncrypted());
        System.out.println(sclient.isEncrypted());
        */
        return serverFC.map.get(client).remaining() > 2;
        
      }
    }.blockTillTrue(5000, 100);
    
    String st = serverFC.map.get(client).getAsString(serverFC.map.get(client).remaining());
    assertEquals(TCPTests.SMALL_TEXT, st);
    
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
  
  @Test
  public void doLateSSLhandshake() throws IOException, InterruptedException, ExecutionException {
    SSLServer server = new SSLServer(SE.createTCPServer("localhost", port), sslCtx, false);
    final AtomicReference<SSLClient> servers_client = new AtomicReference<SSLClient>();
    final AtomicReference<String> serversEncryptedString = new AtomicReference<String>();
    final AtomicReference<String> clientsEncryptedString = new AtomicReference<String>();
    
    server.setClientAcceptor(new ClientAcceptor() {
      @Override
      public void accept(Client c) {
        final SSLClient sslc = (SSLClient) c;
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
                sslc.doHandShake().addListener(new Runnable() {
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
    
    final SSLClient sslclient = new SSLClient(SE.createTCPClient("localhost", port), this.sslCtx.createSSLEngine("localhost", port), false, true);
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
            sslclient.doHandShake().addListener(new Runnable() {
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
      sslclient.connect().get();
    } catch (ExecutionException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    //System.out.println("WRITE!!");
    sslclient.write(ByteBuffer.wrap("DO_SSL".getBytes())).get();
    
    
    new TestCondition(){
      @Override
      public boolean get() {
        if(servers_client.get() != null) {
          System.out.println(servers_client.get().getReadBufferSize());
        }
        return clientsEncryptedString.get() != null && serversEncryptedString.get() != null;
      }
    }.blockTillTrue(5000, 100);
    assertEquals(clientsEncryptedString.get(), serversEncryptedString.get());
  }
}
