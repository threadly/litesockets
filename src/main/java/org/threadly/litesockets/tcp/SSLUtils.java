package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


/**
 * Common utilities for SSL connections. 
 */
public class SSLUtils {
  public static final String SSL_HANDSHAKE_ERROR = "Problem doing SSL Handshake";
  public static final TrustManager[] OPEN_TRUST_MANAGER = new TrustManager [] {new SSLUtils.FullTrustManager() };
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
   * This trust manager just trusts everyone and everything.  You probably 
   * should not be using it unless you know what your doing.
   * 
   * @author lwahlmeier
   */
  public static class FullTrustManager implements X509TrustManager, TrustManager {

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
   * This is a helper function used to complete the SSLHandshake internally.  This is what we do when 
   * the HandshakeStatus is NEED_UNWRAP, which basically means need Read.
   * 
   * @param networkDataBuffer this is the networkBuffer that was last read off the wire. 
   * @param peerData This is the buffer to write into after we 
   * @param ssle the SSLEngine used in the handshake.
   * @param channel the socketChannel the handshake is happening on.
   * @return the current HandshalStatus
   * @throws IOException this will happen if there is any problems decrypting the data or reading/writing to the socket.
   */
  public static SSLEngineResult.HandshakeStatus doHandShakeRead(ByteBuffer networkDataBuffer, 
      ByteBuffer peerData, SSLEngine ssle, SocketChannel channel) throws IOException {
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
  
  /**
   * This is a helper function used to complete the SSLHandshake internally.  This is what 
   * we do when the HandshakeStatus is NEED_WRAP, which basically means need write.
   *
   * @param appBuffer This is the buffer that is encrypted.  It should essentially be empty as wrap will add the handshake data needed to it. 
   * @param networkData this is the buffer that will store the encrypted data and be written to the socket. 
   * @param ssle the SSLEngine used in the handshake.
   * @param channel the socketChannel the handshake is happening on.
   * @return the current HandshalStatus
   * @throws IOException this will happen if there is any problems decrypting the data or reading/writing to the socket.
   */
  public static SSLEngineResult.HandshakeStatus doHandShakeWrite(ByteBuffer appBuffer, ByteBuffer networkData, 
      SSLEngine ssle, SocketChannel channel) throws IOException {
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

}
