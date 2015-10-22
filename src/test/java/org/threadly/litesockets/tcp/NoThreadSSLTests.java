package org.threadly.litesockets.tcp;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.Server;

public class NoThreadSSLTests extends SSLTests {
  NoThreadSocketExecuter ntSE;
  
  @Before
  public void start() throws Exception {
    PS = new PriorityScheduler(5);
    ntSE = new NoThreadSocketExecuter(); 
    SE = ntSE;
    SE.start();
    PS.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        ntSE.select(10);
      }}, 10, 10);
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
  
  @Override
  public void largeWriteTest() throws Exception {
    super.largeWriteTest();
  }
  
  @Override
  public void doLateSSLhandshake() throws IOException, InterruptedException, ExecutionException, TimeoutException {
    super.doLateSSLhandshake();
  }
  
//  @Override
//  public void useTCPClientPendingReads() throws IOException{
//    super.useTCPClientPendingReads();
//  }

}
