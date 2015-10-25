package org.threadly.litesockets.tcp;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


/**
 * Common utilities for SSL connections. 
 */
public class SSLUtils {
  
  public static final String SSL_HANDSHAKE_ERROR = "Problem doing SSL Handshake";
  public static final SSLContext OPEN_SSL_CTX; 
  
  static {
    try {
      //We dont allow SSL by default connections anymore
      OPEN_SSL_CTX = SSLContext.getInstance("TLS");
      OPEN_SSL_CTX.init(null, getOpenTrustManager(), null);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    } catch (KeyManagementException e) {
      throw new RuntimeException(e);
    }
  }
  
  public static TrustManager[] getOpenTrustManager() {
    return new TrustManager [] {new SSLUtils.FullTrustManager() };
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
  
  private SSLUtils(){}
  
  /**
   * This trust manager just trusts everyone and everything.  You probably 
   * should not be using it unless you know what your doing.
   * 
   * @author lwahlmeier
   */
  public static class FullTrustManager implements X509TrustManager, TrustManager {

    @Override
    public void checkClientTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
      //No Exception means we are ok
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] chain, final String authType)
        throws CertificateException {
      //No Exception means we are ok
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
