package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;


/**
 * This is pretty generic SSL server implementation.  It allows you to provide the SSLContext to the server that will be 
 * applied to the clients that connect.  You can also use it as a TLS server and not complete the handshake on connection.
 * 
 */
public class SSLServer extends TCPServer {
  private final SSLContext sctx;
  private final boolean completeHandshake;

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
  public SSLServer(String host, int port, SSLContext sslctx, boolean completeHandshake) throws IOException {
    super(host, port);
    sctx = sslctx;
    this.completeHandshake = completeHandshake;
  }

  /**
   * Constructs an SSL server using an existing Socket.
   * 
   * @param server the ServerSocketChannel to use for this connection.
   * @param sslctx the {@link SSLContext} to apply to the clients that connect.
   * @param completeHandshake if {@code true} the SSL handshake will be completed on connection. If {@code false} the client will not 
   * complete the handshake and will require that you call {@link SSLClient#doHandShake()} once the handshake needs to be done.
   * @throws IOException is thrown if there are any problems binding to the network socket.
   */
  public SSLServer(ServerSocketChannel server, SSLContext sslctx, boolean completeHandshake) throws IOException {
    super(server);
    sctx = sslctx;
    this.completeHandshake = completeHandshake;
  }

  @Override
  public void acceptChannel(final SelectableChannel c) {
    String remote = ((SocketChannel)c).socket().getRemoteSocketAddress().toString();
    int port = ((SocketChannel)c).socket().getPort();
    ClientAcceptor ca = getClientAcceptor();
    if(ca != null) {
      final SSLEngine ssle = sctx.createSSLEngine(remote, port);
      try {
        final SSLClient client = new SSLClient((SocketChannel)c, ssle, false, completeHandshake);
        getClientAcceptor().accept(client);
      } catch (IOException e1) {
        e1.printStackTrace();
      }
    }
  }
}
