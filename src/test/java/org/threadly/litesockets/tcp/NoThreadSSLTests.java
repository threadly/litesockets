package org.threadly.litesockets.tcp;

import java.io.FileInputStream;
import java.security.KeyStore;

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
  
  @Override
  public void largeWriteTest() throws Exception{
    super.largeWriteTest();
  }
}
