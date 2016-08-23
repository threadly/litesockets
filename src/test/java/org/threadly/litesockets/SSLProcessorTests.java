package org.threadly.litesockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SSLProcessor;
import org.threadly.litesockets.utils.SSLUtils;

public class SSLProcessorTests {
  static final String STRING = "hello";
  static final ByteBuffer STRINGBB = ByteBuffer.wrap(STRING.getBytes());
  static final String LARGE_STRING;
  static final ByteBuffer LARGE_STRINGBB;
  static final String[] SIMPLE_ENCRYPT = new String[] {"SSL_DH_anon_WITH_DES_CBC_SHA"};
  static final String[] OTHER_ENCRYPT = new String[] {"SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA"};
  static {
    StringBuilder sb = new StringBuilder();
    for(int i=0; i<100; i++) {
      sb.append(STRING);
    }
    LARGE_STRING = sb.toString();
    LARGE_STRINGBB = ByteBuffer.wrap(LARGE_STRING.getBytes());
  }
  SocketExecuterCommonBase SE;
  TrustManager[] myTMs = new TrustManager [] {new SSLUtils.FullTrustManager() };
  KeyStore KS;
  KeyManagerFactory kmf;
  SSLContext sslCtx;
  
  @Before
  public void start() throws Exception {
    SE = new NoThreadSocketExecuter();
    SE.start();
    KS = KeyStore.getInstance(KeyStore.getDefaultType());
    System.out.println(ClassLoader.getSystemClassLoader().getResource("keystore.jks"));
    String filename = ClassLoader.getSystemClassLoader().getResource("keystore.jks").getFile();
    FileInputStream ksf = new FileInputStream(filename);
    KS.load(ksf, "password".toCharArray());
    kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(KS, "password".toCharArray());

    sslCtx = SSLContext.getInstance("SSL");
    sslCtx.init(kmf.getKeyManagers(), myTMs, null);
    System.out.println(Arrays.toString(sslCtx.createSSLEngine().getSupportedCipherSuites()));
  }
  
  @After
  public void stop() {
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test
  public void notEncrypted() {
    FakeClient fc = new FakeClient(SE);
    SSLProcessor sp = new SSLProcessor(fc, sslCtx.createSSLEngine());
    MergedByteBuffers mbb = sp.encrypt(STRINGBB.duplicate());
    assertEquals(STRING, mbb.copy().getAsString(mbb.remaining()));
    MergedByteBuffers mbb2 = sp.decrypt(mbb);
    assertEquals(STRING, mbb2.copy().getAsString(mbb2.remaining()));
  }
  
  @Test
  public void encrypted() throws IOException {
    FakeClient fc = new FakeClient(SE);
    SSLEngine see = sslCtx.createSSLEngine();
    see.setEnabledCipherSuites(SIMPLE_ENCRYPT);
    see.setUseClientMode(true);
    SSLProcessor sp = new SSLProcessor(fc, see);
    fc.setSSLProcessor(sp);
    FakeClient fc2 = new FakeClient(SE);
    SSLEngine see2 = sslCtx.createSSLEngine();
    see2.setEnabledCipherSuites(SIMPLE_ENCRYPT);
    see2.setUseClientMode(false);
    SSLProcessor sp2 = new SSLProcessor(fc2, see2);
    fc2.setSSLProcessor(sp2);
    assertFalse(sp2.isEncrypted());
    assertFalse(sp.isEncrypted());
    assertFalse(sp.handShakeStarted());
    sp.doHandShake();
    assertTrue(sp.handShakeStarted());
    assertFalse(sp2.handShakeStarted());
    sp2.doHandShake();
    assertTrue(sp2.handShakeStarted());
    
    assertFalse(sp2.isEncrypted());
    assertFalse(sp.isEncrypted());
    while(true) {
      if(fc.canWrite()) {
        ByteBuffer bb = fc.getWriteBuffer();
        fc2.addReadBuffer(bb);
        sp2.decrypt(fc2.getRead());
      } else if(fc2.canWrite()) {
        ByteBuffer bb = fc2.getWriteBuffer();
        fc.addReadBuffer(bb);
        sp.decrypt(fc.getRead());
      } else {
        break;
      }
    }
    assertTrue(sp2.isEncrypted());
    assertTrue(sp.isEncrypted());
    MergedByteBuffers mbb = sp.encrypt(STRINGBB.duplicate());
    byte[] ba = new byte[mbb.remaining()]; 
    mbb.copy().get(ba);
    assertFalse(Arrays.equals(STRINGBB.array(), ba));

    MergedByteBuffers dmbb = sp2.decrypt(mbb);
    assertEquals(STRING, dmbb.getAsString(dmbb.remaining()));
    
  }

  @Test
  public void noCommonCipher() throws IOException {
    FakeClient fc = new FakeClient(SE);
    SSLEngine see = sslCtx.createSSLEngine();
    see.setEnabledCipherSuites(SIMPLE_ENCRYPT);
    see.setUseClientMode(true);
    SSLProcessor sp = new SSLProcessor(fc, see);
    fc.setSSLProcessor(sp);
    FakeClient fc2 = new FakeClient(SE);
    SSLEngine see2 = sslCtx.createSSLEngine();
    see2.setEnabledCipherSuites(OTHER_ENCRYPT);
    see2.setUseClientMode(false);
    SSLProcessor sp2 = new SSLProcessor(fc2, see2);
    fc2.setSSLProcessor(sp2);
    assertFalse(sp2.isEncrypted());
    assertFalse(sp.isEncrypted());
    assertFalse(sp.handShakeStarted());
    sp.doHandShake();
    assertTrue(sp.handShakeStarted());
    assertFalse(sp2.handShakeStarted());
    sp2.doHandShake();
    assertTrue(sp2.handShakeStarted());
    
    assertFalse(sp2.isEncrypted());
    assertFalse(sp.isEncrypted());
    while(true) {
      if(fc.canWrite()) {
        ByteBuffer bb = fc.getWriteBuffer();
        fc2.addReadBuffer(bb);
        sp2.decrypt(fc2.getRead());
      } else if(fc2.canWrite()) {
        ByteBuffer bb = fc2.getWriteBuffer();
        fc.addReadBuffer(bb);
        sp.decrypt(fc.getRead());
      } else {
        break;
      }
    }
    assertFalse(sp2.isEncrypted());
    assertFalse(sp.isEncrypted());
    
  }


  @Test
  public void largeEncrypted() throws IOException {
    FakeClient fc = new FakeClient(SE);
    SSLEngine see = sslCtx.createSSLEngine();
    see.setEnabledCipherSuites(SIMPLE_ENCRYPT);
    see.setUseClientMode(true);
    SSLProcessor sp = new SSLProcessor(fc, see);
    fc.setSSLProcessor(sp);
    FakeClient fc2 = new FakeClient(SE);
    SSLEngine see2 = sslCtx.createSSLEngine();
    see2.setEnabledCipherSuites(SIMPLE_ENCRYPT);
    see2.setUseClientMode(false);
    SSLProcessor sp2 = new SSLProcessor(fc2, see2);
    fc2.setSSLProcessor(sp2);
    sp.doHandShake();
    sp2.doHandShake();
    while(true) {
      if(fc.canWrite()) {
        ByteBuffer bb = fc.getWriteBuffer();
        fc2.addReadBuffer(bb);
        sp2.decrypt(fc2.getRead());
      } else if(fc2.canWrite()) {
        ByteBuffer bb = fc2.getWriteBuffer();
        fc.addReadBuffer(bb);
        sp.decrypt(fc.getRead());
      } else {
        break;
      }
    }
    MergedByteBuffers mbb = sp.encrypt(LARGE_STRINGBB.duplicate());
    byte[] ba = new byte[mbb.remaining()]; 
    mbb.copy().get(ba);
    assertFalse(Arrays.equals(LARGE_STRINGBB.array(), ba));

    MergedByteBuffers dmbb = new MergedByteBuffers();
    while(mbb.remaining() > 0) {
      MergedByteBuffers tmpmbb = sp2.decrypt(mbb.pull(1));
      dmbb.add(tmpmbb);
    }
    assertEquals(LARGE_STRING, dmbb.getAsString(dmbb.remaining()));
    
  }
  
  
  public static class FakeClient extends Client {
    
    MergedByteBuffers writeBuffers = new MergedByteBuffers(false);
    SSLProcessor sp;

    public FakeClient(SocketExecuterCommonBase se) {
      super(se);
    }
    
    public void setSSLProcessor(SSLProcessor sp) {
      this.sp = sp;
    }
    
    @Override
    public void addReadBuffer(ByteBuffer  bb) {
      super.addReadBuffer(bb);
    }

    @Override
    public boolean canWrite() {
      return writeBuffers.remaining() > 0;
    }

    @Override
    public boolean hasConnectionTimedOut() {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public ListenableFuture<Boolean> connect() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    protected void setConnectionStatus(Throwable t) {
      // TODO Auto-generated method stub
      
    }

    @Override
    public void setConnectionTimeout(int timeout) {
      // TODO Auto-generated method stub
      
    }

    @Override
    public int getTimeout() {
      // TODO Auto-generated method stub
      return 0;
    }

    @Override
    public int getWriteBufferSize() {
      // TODO Auto-generated method stub
      return 0;
    }

    @Override
    protected ByteBuffer getWriteBuffer() {
      return writeBuffers.pop();
    }

    @Override
    protected void reduceWrite(int size) {
      // TODO Auto-generated method stub
      
    }

    @Override
    protected SocketChannel getChannel() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public WireProtocol getProtocol() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public void close() {
      // TODO Auto-generated method stub
      
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
      // TODO Auto-generated method stub
      return null;
    }
    
    @Override
    public ListenableFuture<?> write(final ByteBuffer bb) {
      writeBuffers.add(sp.encrypt(bb));
      return FutureUtils.immediateResultFuture(true);
    }

    @Override
    public ClientOptions clientOptions() {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    protected void doSocketRead() {
      // TODO Auto-generated method stub
      
    }

    @Override
    protected void doSocketWrite() {
      // TODO Auto-generated method stub
      
    }

    @Override
    public ListenableFuture<?> lastWriteFuture() {
      // TODO Auto-generated method stub
      return null;
    }
    
  }
  
  
}
