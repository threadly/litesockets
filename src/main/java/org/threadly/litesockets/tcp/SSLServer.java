package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.threadly.litesockets.Client;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.WireProtocol;


/**
 * This is pretty generic SSL server implementation.  It allows you to provide the SSLContext to the server that will be 
 * applied to the clients that connect.  You can also use it as a TLS server and not complete the handshake on connection.
 * 
 */
public class SSLServer extends Server {
  private final SSLContext sctx;
  private final boolean completeHandshake;
  private final TCPServer server;
  private final ClientAcceptor tcpClientAcceptor = new SSLAcceptor();
  private volatile ClientAcceptor localAcceptor = null;

  /**
   * Constructs an SSL server.
   * 
   * @param host the host address to bind to.
   * @param port the port number to bind to.
   * @param sslctx the {@link SSLContext} to apply to the clients that connect.
   * @param completeHandshake if {@code true} the SSL handshake will be completed on connection. If {@code false} the client will not 
   * complete the handshake and will require that you call {@link SSLClient#doHandShake()} once the handshake needs to be done.
   * @throws IOException is thrown if there are any problems binding to the network socket.
   */
  public SSLServer(TCPServer server, SSLContext sslctx, boolean completeHandshake) throws IOException {
    this.completeHandshake = completeHandshake;
    this.server = server;
    sctx = sslctx;
    localAcceptor = server.getClientAcceptor();
    server.setClientAcceptor(this.tcpClientAcceptor);
    server.start();
  }
  
  public class SSLAcceptor implements ClientAcceptor{

    @Override
    public void accept(Client client) {
      TCPClient tc = (TCPClient) client;
      ClientAcceptor ca = getClientAcceptor();
      if(ca != null) {
        final SSLEngine ssle = sctx.createSSLEngine(tc.getSocket().getRemoteSocketAddress().toString(), tc.getSocket().getPort());
        final SSLClient sslClient = new SSLClient(tc, ssle, completeHandshake, false);
        getClientAcceptor().accept(sslClient);
      }
    }
    
  }

  @Override
  public void start() {
    server.start();
  }

  @Override
  public SocketExecuter getSocketExecuter() {
    return server.getSocketExecuter();
  }

  @Override
  public ServerCloser getCloser() {
    return server.getCloser();
  }

  @Override
  public void setCloser(ServerCloser closer) {
    server.setCloser(closer);
  }

  @Override
  protected void acceptChannel(SelectableChannel c) {
    
  }

  @Override
  public WireProtocol getServerType() {
    return WireProtocol.FAKE;
  }

  @Override
  protected SelectableChannel getSelectableChannel() {
    return server.getSelectableChannel();
  }

  @Override
  public ClientAcceptor getClientAcceptor() {
    return localAcceptor;
  }

  @Override
  public void setClientAcceptor(ClientAcceptor clientAcceptor) {
    localAcceptor = clientAcceptor;
  }

  @Override
  public void close() {
    server.close();
  }

  @Override
  public boolean isClosed() {
    return server.isClosed();
  }
  
  public TCPServer getTCPServer() {
    return server;
  }
}
