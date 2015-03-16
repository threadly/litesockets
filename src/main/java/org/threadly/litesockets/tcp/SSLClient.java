package org.threadly.litesockets.tcp;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterBase;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 *
 */
public class SSLClient extends TCPClient implements Reader{
  private static final String SSL_HANDSHAKE_ERROR = "Problem doing SSL Handshake";
  private static final TrustManager[] OPEN_TRUST_MANAGER = new TrustManager [] {new GenericTrustManager() };
  private static final SSLContext OPEN_SSL_CTX; 

  private final MergedByteBuffers decryptedReadList = new MergedByteBuffers();
  public final MergedByteBuffers tmpWriteBuffers = new MergedByteBuffers();
  public final SSLEngine ssle;
  private final SettableListenableFuture<SSLSession> handshakeFuture = new SettableListenableFuture<SSLSession>();
  private final AtomicBoolean doneHandshake = new AtomicBoolean(false);
  private volatile long handShakeStart = -1;
  
  private ByteBuffer writeBuffer;
  
  private final ByteBuffer encryptedReadBuffer;
  private ByteBuffer decryptedReadBuffer;
  
  private volatile Reader sslReader; 

  public static void disableSNI() {
    System.setProperty ("jsse.enableSNIExtension", "false");
  }
  
  static {
    try {
      //We dont allow SSL by default connections anymore
      OPEN_SSL_CTX = SSLContext.getInstance("TLS");
      OPEN_SSL_CTX.init(null, OPEN_TRUST_MANAGER, null);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    } catch (KeyManagementException e) {
      throw new RuntimeException(e);
    }
  }

  public SSLClient(String host, int port) throws IOException {
    this(host, port, OPEN_SSL_CTX.createSSLEngine(host, port));
  }
  
  public SSLClient(String host, int port, SSLEngine ssle) throws IOException {
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT);
  }

  public SSLClient(String host, int port, SSLEngine ssle, int timeout) throws IOException {
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT, true);
  }
  
  public SSLClient(String host, int port, SSLEngine ssle, int timeout, boolean doHandshake) throws IOException {
    super(host, port, timeout);
    this.ssle = ssle;
    ssle.setUseClientMode(true);
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+50);
  }

  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL) throws IOException {
    this(client, ssle, clientSSL, true);
  }
  
  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL, boolean doHandshake) throws IOException {
    super(client);
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+50);
  }

  public SSLClient(TCPClient client, SSLEngine ssle, boolean clientSSL, boolean doHandshake) throws IOException {
    super(client.getChannel());
    if(client.getReadBufferSize() > 0 || client.getWriteBufferSize() > 0) {
      throw new IllegalStateException("Can not add a TCPClient with pending Reads or Writes!");
    }
    if(client.isClosed()) {
      throw new IllegalStateException("Can not add closed TCPClient to sslConstructor");
    }
    client.fakeClose();
    setCloser(client.getCloser());
    setReader(client.getReader());
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+50);
  }
  
  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()*1.5) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()*3);
    }
    return decryptedReadBuffer;
  }
  
  public boolean isEncrypted() {
    if(doneHandshake.get() && ssle.getSession().getProtocol().equals("NONE")) {
      return false;
    }
    return true;
  }
  
  public ListenableFuture<SSLSession> doHandShake() throws IOException {
    if(doneHandshake.compareAndSet(false, true)) {
      handShakeStart = Clock.lastKnownForwardProgressingMillis(); 
      ssle.beginHandshake();
      if(ssle.getHandshakeStatus() == NEED_WRAP) {
        writeForce(ByteBuffer.allocate(0));
      }
      if(this.ce != null) {
        ce.getThreadScheduler().schedule(new Runnable() {
          @Override
          public void run() {
            if(!handshakeFuture.isDone()) {
              handshakeFuture.setFailure(new TimeoutException("Timed out doing SSLHandshake!!!"));
              close();
            }
          }}, setTimeout);
      }
    }
    return handshakeFuture;
  }
  
  @Override
  protected void setThreadExecuter(SubmitterExecutorInterface sei) {
    super.setThreadExecuter(sei);
  }
  
  @Override
  protected void setSocketExecuter(SocketExecuterBase ce) {
    super.setSocketExecuter(ce);
    if(doneHandshake.get() && !handshakeFuture.isDone()) {
      ce.getThreadScheduler().schedule(new Runnable() {
        @Override
        public void run() {
          if(!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(new TimeoutException("Timed out doing SSLHandshake!!!"));
            close();
          }
        }}, setTimeout);
    }
  }
  
  private void runTasks() {
    SSLEngineResult.HandshakeStatus hs = ssle.getHandshakeStatus();
    while(hs == NEED_TASK) {
      Runnable task = ssle.getDelegatedTask();
      task.run();
      hs = ssle.getHandshakeStatus();
    }
  }
  
  private ByteBuffer getAppWriteBuffer() {
    if(this.writeBuffer == null || this.writeBuffer.remaining() < ssle.getSession().getPacketBufferSize()*1.5) {
      this.writeBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()*3);
    }
    return writeBuffer;
  }
  
  @Override
  public void writeForce(ByteBuffer buffer) {
    if (!this.isClosed()) {
      if(this.doneHandshake.get()) {
        synchronized(ssle) {
          if(ssle.getHandshakeStatus() != NOT_HANDSHAKING && buffer.remaining() > 0) {
            this.tmpWriteBuffers.add(buffer);
            return;
          }
          ByteBuffer oldBB = buffer.duplicate();
          ByteBuffer newBB; 
          ByteBuffer tmpBB;
          while (ssle.getHandshakeStatus() == NEED_WRAP || oldBB.remaining() > 0) {
            newBB = getAppWriteBuffer();
            try {
              tmpBB = newBB.duplicate();
              SSLEngineResult res = ssle.wrap(oldBB, newBB);
              tmpBB.limit(newBB.position());
              //System.out.println(this+"WROTE:"+tmpBB);
              super.writeForce(tmpBB);
              if(res.getHandshakeStatus() == FINISHED) {
                ByteBuffer localBB = null;
                synchronized(tmpWriteBuffers) {
                  if(tmpWriteBuffers.remaining() > 0) {
                    localBB = (tmpWriteBuffers.pull(tmpWriteBuffers.remaining()));
                  }
                }
                if(localBB != null && localBB.remaining() > 0) {
                  oldBB = localBB;
                }
                if(!handshakeFuture.isDone()) {
                  handshakeFuture.setResult(ssle.getSession());
                }
              }else {
                while (ssle.getHandshakeStatus() == NEED_TASK) {
                  runTasks();
                }
              }
            } catch(Exception e) {
              if(!handshakeFuture.isDone()) {
                handshakeFuture.setFailure(e);
                this.close();
              }
              break;
            }
          }

        }
      } else {
        System.out.println(this+"ONWRITE:NOSSL");
        super.writeForce(buffer);
      }
    }
  }
  
  @Override
  public ByteBuffer getRead() {
    synchronized(decryptedReadList) {
      return decryptedReadList.pop();
    }
  }
  
  @Override
  public void setReader(Reader reader) {
    this.sslReader = reader;
  }

  @Override
  public void onRead(Client client) {
    ByteBuffer client_buffer = super.getRead();
    if(this.doneHandshake.get()) {
      try {
        while(client_buffer.hasRemaining()) {
          if(client_buffer.remaining() > encryptedReadBuffer.remaining()) {
            byte[] ba = new byte[encryptedReadBuffer.remaining()];
            client_buffer.get(ba);
            encryptedReadBuffer.put(ba);
          } else {
            encryptedReadBuffer.put(client_buffer);
          }
          while(encryptedReadBuffer.position() > 0) {
            encryptedReadBuffer.flip();
            ByteBuffer dbb = getDecryptedByteBuffer();
            ByteBuffer newBB = dbb.duplicate();

            SSLEngineResult res = ssle.unwrap(encryptedReadBuffer, dbb);
            if(res.getHandshakeStatus() == FINISHED) {
              ByteBuffer localBB = null;
              synchronized(tmpWriteBuffers) {
                if(tmpWriteBuffers.remaining() > 0) {
                  localBB = (tmpWriteBuffers.pull(tmpWriteBuffers.remaining()));
                }
              }
              if(localBB != null && localBB.remaining() > 0) {
                writeForce(localBB);
              }
              if(!handshakeFuture.isDone()) {
                handshakeFuture.setResult(ssle.getSession());
              }
            } else if (ssle.getHandshakeStatus() != NOT_HANDSHAKING){
              while (ssle.getHandshakeStatus() == NEED_TASK) {
                runTasks();
              }
              if(ssle.getHandshakeStatus() == NEED_WRAP) {
                writeForce(ByteBuffer.allocate(0));
              }
            }
            newBB.limit(dbb.position());
            encryptedReadBuffer.compact();
            if(newBB.hasRemaining()) {
              if(sslReader != null) {
                synchronized(decryptedReadList) {
                  decryptedReadList.add(newBB);
                }
                sslReader.onRead(this);
              }
            } else if (res.getStatus() == Status.BUFFER_UNDERFLOW || encryptedReadBuffer.remaining() == 0 || (ssle.getHandshakeStatus() != NOT_HANDSHAKING & ssle.getHandshakeStatus() != NEED_UNWRAP)){
              break;
            }
          }
        }
      } catch (SSLException e) {
        if(!handshakeFuture.isDone()) {
          handshakeFuture.setFailure(e);
        }
        ExceptionUtils.handleException(e);
        this.close();
      }
    } else {
      System.out.println(this+"ONREAD:NOSSL");
      decryptedReadList.add(client_buffer);
      sslReader.onRead(this);
    }
  }
  

  public static class GenericTrustManager implements X509TrustManager, TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      //No Exception means we are ok
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      //No Exception means we are ok
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
  
  
  /**
   * Java 7 introduced SNI by default when you establish SSl connections.
   * The problem is there is no way to turn it off or on at a per connection level.
   * So if you are knowingly going to connect to a server that has a non SNI valid cert
   * you have to disable SNI for the whole VM. 
   * 
   * The default is whatever the VM is started with you can enable it by running this method.
   * 
   */
  public static void enableSNI() {
    System.setProperty ("jsse.enableSNIExtension", "true");
  }
  
  /**
   * Java 7 introduced SNI by default when you establish SSl connections.
   * The problem is there is no way to turn it off or on at a per connection level.
   * So if you are knowingly going to connect to a server that has a non SNI valid cert
   * you have to disable SNI for the whole VM. 
   * 
   * The default is whatever the VM is started with you can disable it by running this method.
   * 
   */

}
