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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.threadly.litesockets.Client;
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
  private final SSLEngine ssle;
  
  private ByteBuffer tmpAppBuffer;
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
    } catch (KeyManagementException | NoSuchAlgorithmException e) {
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
    super(host, port, timeout);
    this.ssle = ssle;
    ssle.setUseClientMode(true);
    doHandShake();
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
  }

  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL) throws IOException {
    super(client);
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    doHandShake();
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
  }

  public SSLClient(TCPClient client, SSLEngine ssle, boolean clientSSL) throws IOException {
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
    doHandShake();
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
  }
  
  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()*2);
    }
    return decryptedReadBuffer;
  }
  
  private static SSLEngineResult.HandshakeStatus doHandShakeRead(ByteBuffer networkDataBuffer, ByteBuffer peerData, SSLEngine ssle, SocketChannel channel) throws IOException {
    peerData.clear();
    SSLEngineResult.HandshakeStatus hs;
    if (channel.read(networkDataBuffer) < 0) {
      //Got close
      throw new SSLHandshakeException(SSL_HANDSHAKE_ERROR);
    }
    networkDataBuffer.flip();
    SSLEngineResult res = ssle.unwrap(networkDataBuffer, peerData);
    networkDataBuffer.compact();
    hs = res.getHandshakeStatus();
    if(res.getStatus() != SSLEngineResult.Status.OK  && 
        res.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
      throw new SSLHandshakeException(SSL_HANDSHAKE_ERROR+":"+res.getStatus());
    }
    return hs;
  } 
  
  private static SSLEngineResult.HandshakeStatus doHandShakeWrite(ByteBuffer appBuffer, ByteBuffer networkData, SSLEngine ssle, SocketChannel channel) throws IOException {
    networkData.clear();
    SSLEngineResult.HandshakeStatus hs;
    SSLEngineResult res = ssle.wrap(appBuffer, networkData);
    hs = res.getHandshakeStatus();
    
    if(res.getStatus() == SSLEngineResult.Status.OK) {
      networkData.flip();
      while (networkData.hasRemaining()) {
        if (channel.write(networkData) < 0) {
          throw new SSLHandshakeException(SSL_HANDSHAKE_ERROR);
        }
      }
    } else {
      throw new SSLHandshakeException(SSL_HANDSHAKE_ERROR+":"+res.getStatus());
    }
    return hs;
  }
  
  
  private void doHandShake() throws IOException {
    ByteBuffer appDataBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize());
    ByteBuffer netDataBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
    
    ByteBuffer eNetworkData = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
    ByteBuffer ePeerAppData = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize());
    
    //Start the handShake
    ssle.beginHandshake();
    SSLEngineResult.HandshakeStatus hs = ssle.getHandshakeStatus();
    Selector select = Selector.open();
    
    while (hs != FINISHED && hs != NOT_HANDSHAKING ) {
      if(Clock.lastKnownForwardProgressingMillis() - startTime > setTimeout) {
        throw new IOException("Timeout doing SSLHandshake!");
      }

      select.selectedKeys().clear();
      if(hs == NEED_UNWRAP) {
        channel.register(select, SelectionKey.OP_READ);
        select.select(100);
      } else if (hs == NEED_WRAP) {
        channel.register(select, SelectionKey.OP_WRITE);
        select.select(100);
      }
      channel.register(select, 0);

      while(hs == NEED_UNWRAP ) {
        hs = doHandShakeRead(netDataBuffer, ePeerAppData, ssle, channel);
        if(hs == NEED_TASK) {
          runTasks();
          hs = ssle.getHandshakeStatus();
        }
      }
      while(hs ==  NEED_WRAP) {
        hs = doHandShakeWrite(appDataBuffer, eNetworkData, ssle, channel);
        if(hs == NEED_TASK) {
          runTasks();
          hs = ssle.getHandshakeStatus();
        }
      }
    }
    select.close();
    tmpAppBuffer = appDataBuffer;
    tmpAppBuffer.clear();
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
    if(this.writeBuffer == null || this.writeBuffer.remaining() < ssle.getSession().getPacketBufferSize()) {
      this.writeBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()*2);
    }
    return writeBuffer;
  }
  
  @Override
  public void writeForce(ByteBuffer buffer) {
    if (!this.isClosed() && buffer.hasRemaining()) {
      synchronized(ssle) {
        ByteBuffer oldBB = buffer.duplicate();
        ByteBuffer newBB; 
        ByteBuffer tmpBB;
        while (oldBB.remaining() > 0) {
          newBB = getAppWriteBuffer();
          try {
            tmpBB = newBB.duplicate();
            //TODO: Is there a reason to check the return of wrap??
            ssle.wrap(oldBB, newBB);
            tmpBB.limit(newBB.position());
            super.writeForce(tmpBB);
          } catch(Exception e) {
            break;
          }
        }
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
    try {
      ByteBuffer client_buffer = super.getRead();
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
          @SuppressWarnings("unused")
          SSLEngineResult res = ssle.unwrap(encryptedReadBuffer, dbb);
          newBB.limit(dbb.position());
          encryptedReadBuffer.compact();
          if(newBB.hasRemaining()) {
            if(sslReader != null) {
              synchronized(decryptedReadList) {
                decryptedReadList.add(newBB);
              }
              sslReader.onRead(this);
            }
          } else {
            break;
          }
        }
      }
    } catch (SSLException e) {
      ExceptionUtils.handleException(e);
      this.close();
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
