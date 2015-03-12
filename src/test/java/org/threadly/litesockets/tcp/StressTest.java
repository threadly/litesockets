package org.threadly.litesockets.tcp;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.tcp.ssl.SSLUtils.FullTrustManager;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.test.concurrent.TestCondition;
import org.threadly.util.Clock;

public class StressTest {
  public static final String SMALL_TEXT = "TEST111";
  public static final ByteBuffer SMALL_TEXT_BUFFER = ByteBuffer.wrap(SMALL_TEXT.getBytes());
  public static final String LARGE_TEXT;
  public static final ByteBuffer LARGE_TEXT_BUFFER;
  static {
    StringBuffer sb = new StringBuffer();
    for(int i = 0; i<20000; i++) {
      sb.append(SMALL_TEXT);
    }
    LARGE_TEXT = sb.toString();
    LARGE_TEXT_BUFFER = ByteBuffer.wrap(LARGE_TEXT.getBytes());
  }
  PriorityScheduler PS;
  int port = Utils.findTCPPort();
  final String GET = "hello";
  SocketExecuterInterface SE;
  TCPServer server;
  LocalFakeClient serverFC;
  TrustManager[] myTMs = new TrustManager [] {new FullTrustManager() };
  KeyStore KS;
  KeyManagerFactory kmf;
  SSLContext sslCtx;
  
  @Before
  public void start() throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException, KeyManagementException, UnrecoverableKeyException {
    PS = new PriorityScheduler(5, 5, 100000);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    KS = KeyStore.getInstance(KeyStore.getDefaultType());
    String filename = ClassLoader.getSystemClassLoader().getResource("keystore.jks").getFile();
    FileInputStream ksf = new FileInputStream(filename);
    KS.load(ksf, "password".toCharArray());
    kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(KS, "password".toCharArray());
    sslCtx = SSLContext.getInstance("TLS");
    sslCtx.init(kmf.getKeyManagers(), myTMs, null);

    serverFC = new LocalFakeClient(SE);
    //server = new SSLServer("localhost", port, sslCtx);
    server = new TCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);
  }
  
  @After
  public void stop() {    
    SE.stopIfRunning();
    PS.shutdown();
  }
  
  //@Test
  public void bigWrite() throws IOException, InterruptedException {
    final AtomicInteger times = new AtomicInteger(0); 
    //final TCPClient client = new SSLClient("localhost", port);
    final TCPClient client = new TCPClient("localhost", port);
    final LocalFakeClient clientFC = new LocalFakeClient(SE);
    clientFC.addTCPClient(client);
    client.setMaxBufferSize(LARGE_TEXT_BUFFER.remaining()*100);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        long now = Clock.lastKnownForwardProgressingMillis();
        while(Clock.lastKnownForwardProgressingMillis() - now < 10000) {
          try {
            client.writeBlocking(LARGE_TEXT_BUFFER.duplicate());
            times.incrementAndGet();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }});
    
    
    SE.addClient(client);
    new TestCondition(){
      @Override
      public boolean get() {
        return serverFC.map.size() == 1;
      }
    }.blockTillTrue(5000, 100);
    
    TCPClient c2 = null;
    for(Client c: serverFC.map.keySet()) {
      c2 = (TCPClient)c;
      break;
    }
    final TCPClient cf = c2;
    cf.setMaxBufferSize(LARGE_TEXT_BUFFER.remaining()*100);
    new TestCondition(){
      @Override
      public boolean get() {
/*
        System.out.println(serverFC.map.get(cf)+":"+((long)LARGE_TEXT_BUFFER.remaining()*(long)times.get()));
        System.out.println("WRITE:"+client.getWriteBufferSize());
        System.out.println("READ:"+cf.getWriteBufferSize());
*/
        return serverFC.map.get(cf) == ((long)LARGE_TEXT_BUFFER.remaining()*(long)times.get());
      }
    }.blockTillTrue(20000, 100);
    //assertEquals(serverFC.map.get(c2), LARGE_TEXT_BUFFER.remaining()*times);

    System.out.println(client.getStats().getTotalRead());
    System.out.println(client.getStats().getReadRate());
    System.out.println(client.getStats().getTotalWrite());
    System.out.println(client.getStats().getWriteRate());
    System.out.println("-----");    
    System.out.println(cf.getStats().getTotalRead());
    System.out.println(cf.getStats().getReadRate());
    System.out.println(cf.getStats().getTotalWrite());
    System.out.println(cf.getStats().getWriteRate());
    System.out.println("calls:"+serverFC.calls);
    System.out.println("times:"+times.get());
  }
  
  private static class LocalFakeClient extends FakeTCPServerClient {
    public ConcurrentHashMap<Client, Long> map = new ConcurrentHashMap<Client, Long>();
    public long calls = 0;

    public LocalFakeClient(SocketExecuterInterface se) {
      super(se);
    }
    
    @Override
    public void onRead(Client client) {
      map.putIfAbsent(client, 0L);
      calls++;
      MergedByteBuffers bb = client.getRead();
      long l = map.get(client);
      //System.out.println("GotData:"+bb.remaining()+":"+client+":"+client.getReadBufferSize()+":"+(l+bb.remaining()));
      map.put(client, l+bb.remaining());
      /*
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }*/
      //System.out.println("GotData:"+bb.remaining()+":"+client+":"+client.getReadBufferSize()+":"+(l+bb.remaining()));
    }
    
  }

}
