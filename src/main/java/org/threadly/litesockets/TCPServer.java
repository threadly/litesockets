package org.threadly.litesockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.threadly.litesockets.utils.IOUtils;

/**
 * A Simple TCP server.
 * 
 */
public class TCPServer extends Server {
  private final ServerSocketChannel socket;
  private volatile SSLContext sslCtx;
  private volatile String hostName;
  private volatile boolean doHandshake = false;

  /**
   * Creates a new TCP Listen socket on the passed host/port.  This is Listen port is created
   * immediately and will throw an exception if for any reason it can't be opened.
   * 
   * @param host The host address/interface to create this listen port on.
   * @param port The port to use for the listen port.
   * @throws IOException This is throw if for any reason we can't create the listen port.
   */
  protected TCPServer(final SocketExecuterCommonBase se, final String host, final int port) throws IOException {
    super(se);
    socket = ServerSocketChannel.open();
    socket.socket().setReuseAddress(true);
    socket.socket().bind(new InetSocketAddress(host, port), 100);
    socket.configureBlocking(false);
  }

  /**
   * This allows you to provide an already existing {@link ServerSocketChannel}.  It must already be open. 
   * 
   * @param server The {@link ServerSocketChannel} to be used by this TCPServer. 
   * @throws IOException  If anything is wrong with the provided {@link ServerSocketChannel} this will be thrown.
   */
  protected TCPServer(final SocketExecuterCommonBase se, final ServerSocketChannel server) throws IOException{
    super(se);
    server.configureBlocking(false);
    socket = server;
  }

  @Override
  public ServerSocketChannel getSelectableChannel() {
    return socket;
  }

  @Override
  public void close() {
    if(this.setClosed()) {
      getSocketExecuter().stopListening(this);
      IOUtils.closeQuietly(socket);
      callClosers();
    }
  }

  @Override
  public WireProtocol getServerType() {
    return WireProtocol.TCP;
  }

  @Override
  public void acceptChannel(final SelectableChannel c) {
    this.getSocketExecuter().getThreadScheduler().execute(new Runnable() {
      public void run() {
        try {
          final TCPClient client = getSocketExecuter().createTCPClient((SocketChannel)c);
          if(sslCtx != null) {
            SSLEngine ssle;
            if(hostName == null) {
              ssle = sslCtx.createSSLEngine(client.getLocalSocketAddress().getHostName(), client.getLocalSocketAddress().getPort());
            } else {
              ssle = sslCtx.createSSLEngine(hostName, client.getLocalSocketAddress().getPort());
            }
            ssle.setUseClientMode(false);
            client.setSSLEngine(ssle);
            if(doHandshake) {
              client.startSSL();
            }
          }
          if(getClientAcceptor() != null) {
            getClientAcceptor().accept(client);
          }
        } catch (IOException e) {
          //We dont care, client closed before we could do anything with it.
        }
      }
    });
  }
  
  public void setSSLContext(final SSLContext sslctx) {
    this.sslCtx = sslctx;
  }
  
  public void setSSLHostName(final String name) {
    this.hostName = name;
  }
  
  public void setDoHandshake(final boolean hs) {
    this.doHandshake = hs;
  }
}
