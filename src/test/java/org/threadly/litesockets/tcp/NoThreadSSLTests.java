package org.threadly.litesockets.tcp;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.TCPServer;

public class NoThreadSSLTests extends SSLTests {
  NoThreadSocketExecuter ntSE;
  volatile boolean running = true;
  
  @Before
  public void start() throws Exception {
    PS = new PriorityScheduler(5);
    ntSE = new NoThreadSocketExecuter(); 
    SE = ntSE;
    SE.start();
    PS.execute(new Runnable() {
      @Override
      public void run() {
        ntSE.select(10);
        if(running) {
          PS.execute(this);
        }
      }});
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
    running = false;
    for(Server s: serverFC.getAllServers()) {
      s.close();
    }
    
    for(Client c: serverFC.getAllClients()) {
      c.close();
    }
    ntSE.wakeup();
    ntSE.wakeup();
    SE.stopIfRunning();
    PS.shutdownNow();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  //@Test
  public void loop() throws Exception {
    for(int i=0; i<100; i++) {
      this.largeWriteTest();
      this.stop();
      this.start();
    }
  }
  
  @Test
  public void simpleInlineSSLtest() throws Exception {
    NoThreadSocketExecuter lntse = new NoThreadSocketExecuter();
    lntse.start();
    TCPServer server = lntse.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);
    serverFC.addTCPServer(server);
    TCPClient client = lntse.createTCPClient("localhost", port);
    SSLEngine ssle = sslCtx.createSSLEngine();
    ssle.setUseClientMode(true);
    client.setSSLEngine(ssle);
    client.startSSL();
    serverFC.addTCPClient(client);
    final ListenableFuture<?> connected = client.connect();
    assertFalse(client.isEncrypted());
    assertFalse(connected.isDone());
    long start = System.currentTimeMillis();
    while((!connected.isDone() || !client.isEncrypted()) && System.currentTimeMillis() - start < 5000) {
      lntse.select(1);
    }
    System.out.println(System.currentTimeMillis()-start );
    assertTrue(System.currentTimeMillis()-start <= 5000);
    assertTrue(client.isEncrypted());
    TCPClient sclient = serverFC.getClientAt(1);
    assertTrue(sclient.isEncrypted());
    client.write(ByteBuffer.wrap(GET.getBytes()));
    assertEquals(0, serverFC.getClientsBuffer(sclient).remaining());
    start = System.currentTimeMillis();
    while((serverFC.getClientsBuffer(sclient).remaining() == 0) && System.currentTimeMillis() - start < 5000) {
      lntse.select(1);
    }
    String data = serverFC.getClientsBuffer(sclient).getAsString(serverFC.getClientsBuffer(sclient).remaining());
    assertEquals(GET, data);
  }
  
  @Test
  public void preDataInlineSSLtest() throws Exception {
    NoThreadSocketExecuter lntse = new NoThreadSocketExecuter();
    lntse.start();
    TCPServer server = lntse.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    server.setDoHandshake(true);
    serverFC.addTCPServer(server);
    TCPClient client = lntse.createTCPClient("localhost", port);
    SSLEngine ssle = sslCtx.createSSLEngine();
    ssle.setUseClientMode(true);
    client.setSSLEngine(ssle);
    client.startSSL();
    serverFC.addTCPClient(client);
    final ListenableFuture<?> connected = client.connect();
    assertFalse(client.isEncrypted());
    assertFalse(connected.isDone());
    System.out.println("startW");
    client.write(ByteBuffer.wrap(GET.getBytes()));
    System.out.println("stopW");
    long start = System.currentTimeMillis();
    while((!connected.isDone() || !client.isEncrypted()) && System.currentTimeMillis() - start < 5000) {
      lntse.select(1);
    }
    System.out.println(System.currentTimeMillis()-start );
    assertTrue(System.currentTimeMillis()-start <= 5000);
    assertTrue(client.isEncrypted());
    TCPClient sclient = serverFC.getClientAt(1);
    assertTrue(sclient.isEncrypted());
    start = System.currentTimeMillis();
    while((serverFC.getClientsBuffer(sclient).remaining() == 0) && System.currentTimeMillis() - start < 5000) {
      lntse.select(1);
    }
    String data = serverFC.getClientsBuffer(sclient).getAsString(serverFC.getClientsBuffer(sclient).remaining());
    assertEquals(GET, data);
  }
  
  @Test
  public void preDataServerInlineSSLtest() throws Exception {
    NoThreadSocketExecuter lntse = new NoThreadSocketExecuter();
    lntse.start();
    TCPServer server = lntse.createTCPServer("localhost", port);
    server.setSSLContext(sslCtx);
    serverFC.addTCPServer(server);
    TCPClient client = lntse.createTCPClient("localhost", port);
    SSLEngine ssle = sslCtx.createSSLEngine();
    ssle.setUseClientMode(true);
    client.setSSLEngine(ssle);
    serverFC.addTCPClient(client);
    final ListenableFuture<?> connected = client.connect();
    assertFalse(client.isEncrypted());
    assertFalse(connected.isDone());
    long start = System.currentTimeMillis();
    while((!connected.isDone()) && System.currentTimeMillis() - start < 5000) {
      lntse.select(1);
    }
    System.out.println(System.currentTimeMillis()-start );
    assertTrue(System.currentTimeMillis()-start <= 5000);
    assertFalse(client.isEncrypted());
    TCPClient sclient = serverFC.getClientAt(1);
    assertFalse(sclient.isEncrypted());
    sclient.startSSL();
    client.startSSL();
    System.out.println("startW");
    sclient.write(ByteBuffer.wrap(GET.getBytes()));
    System.out.println("endW");
    start = System.currentTimeMillis();
    while((serverFC.getClientsBuffer(client).remaining() == 0) && System.currentTimeMillis() - start < 5000) {
      lntse.select(1);
    }
    String data = serverFC.getClientsBuffer(client).getAsString(serverFC.getClientsBuffer(client).remaining());
    assertEquals(GET, data);
  }
  
  @Override
  public void largeWriteTest() throws Exception{
    super.largeWriteTest();
  }
}
