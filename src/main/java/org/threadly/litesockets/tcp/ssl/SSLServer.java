package org.threadly.litesockets.tcp.ssl;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.threadly.litesockets.tcp.TCPServer;

public class SSLServer extends TCPServer {
  private final SSLContext sctx;
  private final boolean completeHandshake;

  public SSLServer(String host, int port, SSLContext sslctx, boolean completeHandshake) throws IOException {
    super(host, port);
    sctx = sslctx;
    this.completeHandshake = completeHandshake;
  }

  public SSLServer(ServerSocketChannel server, SSLContext sslctx, boolean completeHandshake) throws IOException {
    super(server);
    sctx = sslctx;
    this.completeHandshake = completeHandshake;
  }

  @Override
  public void accept(final SelectableChannel c) {
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
