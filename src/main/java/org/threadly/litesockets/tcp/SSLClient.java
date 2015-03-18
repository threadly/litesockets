package org.threadly.litesockets.tcp;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;

import java.io.IOException;
import java.nio.ByteBuffer;
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
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterBase;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.util.ExceptionUtils;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 *
 */
public class SSLClient extends TCPClient {
  public static final TrustManager[] OPEN_TRUST_MANAGER = new TrustManager [] {new GenericTrustManager() };
  public static final SSLContext OPEN_SSL_CTX; 

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
  
  private final MergedByteBuffers decryptedReadList = new MergedByteBuffers();
  private final MergedByteBuffers tmpWriteBuffers = new MergedByteBuffers();
  private final SSLEngine ssle;
  private final SettableListenableFuture<SSLSession> handshakeFuture = new SettableListenableFuture<SSLSession>();
  private final AtomicBoolean startedHandshake = new AtomicBoolean(false);
  private final AtomicBoolean finishedHandshake = new AtomicBoolean(false);
  private final Reader classReader = new SSLReader();
  private final ByteBuffer encryptedReadBuffer;
  
  private volatile Reader sslReader;
  private ByteBuffer writeBuffer;
  private ByteBuffer decryptedReadBuffer; 

  /**
   * <p>This is a simple SSLConstructor.  It uses the default socket timeout, and very insecure cert
   * checking.  This setup to generally connect to any server, and does not validate anything.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(String host, int port) throws IOException {
    this(host, port, OPEN_SSL_CTX.createSSLEngine(host, port));
  }

  /**
   * <p>This simple SSLConstructor.  It allows you to specify the SSLEngine to use to allow you to
   * validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @param ssle The SSLEngine to use for the connection.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(String host, int port, SSLEngine ssle) throws IOException {
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT);
  }

  /**
   * <p>This constructor allows you to specify the SSLEngine to use to
   * validate the server certs with the parameters you decide.  
   * It also allows you to set the timeout values.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @param ssle The SSLEngine to use for the connection.
   * @param timeout This is the connection timeout.  It is used for the actual connection timeout and separately for the SSLHandshake.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(String host, int port, SSLEngine ssle, int timeout) throws IOException {
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT, true);
  }

  /**
   * <p>This constructor allows you to specify the SSLEngine to use to allow you to
   * validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @param ssle The SSLEngine to use for the connection.
   * @param timeout This is the connection timeout.  It is used for the actual connection timeout and separately for the SSLHandshake.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(String host, int port, SSLEngine ssle, int timeout, boolean doHandshake) throws IOException {
    super(host, port, timeout);
    this.ssle = ssle;
    ssle.setUseClientMode(true);
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(classReader);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+50);
  }

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the SSLEngine 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param client The SocketChannel to use for the SSLClient.
   * @param ssle The SSLEngine to use for the connection.
   * @param clientSSL Set this to true if this is a client side connection, false if its server side.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL) throws IOException {
    this(client, ssle, clientSSL, true);
  }

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the SSLEngine 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param client The SocketChannel to use for the SSLClient.
   * @param ssle The SSLEngine to use for the connection.
   * @param clientSSL Set this to true if this is a client side connection, false if its server side.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL, boolean doHandshake) throws IOException {
    super(client);
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(classReader);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+50);
  }

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the SSLEngine 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param client The TCPClient to use for the SSLClient.
   * @param ssle The SSLEngine to use for the connection.
   * @param clientSSL Set this to true if this is a client side connection, false if its server side.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
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
    super.setReader(classReader);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+50);
  }

  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()*1.5) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()*3);
    }
    return decryptedReadBuffer;
  }

  /**
   * This lets you know if the connection is currently encrypted or not.
   * @return true if the connection is encrypted false if not.
   */
  public boolean isEncrypted() {
    if(startedHandshake.get() && ssle.getSession().getProtocol().equals("NONE")) {
      return false;
    }
    return true;
  }

  /**
   * <p>If doHandshake was set to false in the constructor you can start the handshake by calling this method.
   * The client will not start the handshake till its added to a SocketExecuter.  The future allows you to know
   * when the handshake has finished if if there was an error.  While the handshake is processing all writes to the 
   * socket will queue.</p>
   * 
   * 
   * @return A listenable Future.  If a result was given it succeeded, if there is an error it failed.  The connection is closed on failures.
   * @throws IOException
   */
  public ListenableFuture<SSLSession> doHandShake() {
    if(startedHandshake.compareAndSet(false, true)) {
      try {
        ssle.beginHandshake();
      } catch (SSLException e) {
        this.handshakeFuture.setFailure(e);
      }
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
    if(startedHandshake.get() && !handshakeFuture.isDone()) {
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
    if (!this.isClosed() && startedHandshake.get()) {
      if(!finishedHandshake.get()&& buffer.remaining() != 0) {
        synchronized(tmpWriteBuffers) {
          if(!finishedHandshake.get()) {
            this.tmpWriteBuffers.add(buffer);
            return;
          }
        }
      } else if(finishedHandshake.get() && buffer.remaining() == 0) {
        synchronized(tmpWriteBuffers) {
          if(tmpWriteBuffers.remaining() > 0) {
            buffer = tmpWriteBuffers.pull(tmpWriteBuffers.remaining());
          }
        }
      }
      synchronized(ssle) {
        boolean gotFinished = false;
        ByteBuffer oldBB = buffer.duplicate();
        ByteBuffer newBB; 
        ByteBuffer tmpBB;
        while (ssle.getHandshakeStatus() == NEED_WRAP || oldBB.remaining() > 0) {
          newBB = getAppWriteBuffer();
          try {
            tmpBB = newBB.duplicate();
            SSLEngineResult res = ssle.wrap(oldBB, newBB);

            if(res.getHandshakeStatus() == FINISHED) {
              gotFinished = true;
            } else {
              while (ssle.getHandshakeStatus() == NEED_TASK) {
                runTasks();
              }
            }
            if(tmpBB.hasRemaining()) {
              tmpBB.limit(newBB.position());
              super.writeForce(tmpBB);
            }
          } catch(Exception e) {
            if(!handshakeFuture.isDone()) {
              handshakeFuture.setFailure(e);
              this.close();
            }
            break;
          }
        }
        if(gotFinished) {
          if(finishedHandshake.compareAndSet(false, true)) {
            writeForce(ByteBuffer.allocate(0));
            if(!handshakeFuture.isDone()) {
              handshakeFuture.setResult(ssle.getSession());
            }
          }
        }
      }
    } else if(!isClosed() && !startedHandshake.get()){
      super.writeForce(buffer);
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

  private void doRead() {
    ByteBuffer client_buffer = super.getRead();
    if(this.startedHandshake.get()) {
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
            //We have to check both each time till complete
            if(! handshakeFuture.isDone()) {
              processHandshake(res.getHandshakeStatus());
              processHandshake(ssle.getHandshakeStatus());
            }

            newBB.limit(dbb.position());
            encryptedReadBuffer.compact();
            if(newBB.hasRemaining()) {
              Reader lreader = sslReader;
              if(lreader != null) {
                //Sync not needed here, this is only accessed by ReadThread.
                decryptedReadList.add(newBB);
                lreader.onRead(this);
              }
            } else if (res.getStatus() == Status.BUFFER_UNDERFLOW || encryptedReadBuffer.remaining() == 0){
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
      decryptedReadList.add(client_buffer);
      sslReader.onRead(this);
    }
  }

  private void processHandshake(HandshakeStatus status) {
    switch(status) {
    case FINISHED: {
      if(this.finishedHandshake.compareAndSet(false, true)){
        writeForce(ByteBuffer.allocate(0));
        if(!handshakeFuture.isDone()) {
          handshakeFuture.setResult(ssle.getSession());
        }
      }
    } break;
    case NEED_TASK: {
      while (ssle.getHandshakeStatus() == NEED_TASK) {
        runTasks();
      }
    } break;
    case NEED_WRAP: {
      writeForce(ByteBuffer.allocate(0));
    } break;
    case NOT_HANDSHAKING:
    default: {

    } break;

    }
  }
  
  private class SSLReader implements Reader {
    @Override
    public void onRead(Client client) {
      doRead();
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
  public static void disableSNI() {
    System.setProperty ("jsse.enableSNIExtension", "false");
  }
}
