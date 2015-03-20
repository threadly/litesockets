package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class SSLServer extends TCPServer {
  private final SSLContext sctx;

  public SSLServer(String host, int port, SSLContext sslctx) throws IOException {
    super(host, port);
    sctx = sslctx;
  }

  public SSLServer(ServerSocketChannel server, SSLContext sslctx) throws IOException {
    super(server);
    sctx = sslctx;
  }

  @Override
  public void accept(final SelectableChannel c) {
    String remote = ((SocketChannel)c).socket().getRemoteSocketAddress().toString();
    int port = ((SocketChannel)c).socket().getPort();
    ClientAcceptor ca = getClientAcceptor();
    if(ca != null) {
      final SSLEngine ssle = sctx.createSSLEngine(remote, port);
      try {
        final SSLClient client = new SSLClient((SocketChannel)c, ssle, false, false);
        getClientAcceptor().accept(client);
      } catch (IOException e1) {
        e1.printStackTrace();
      }
    }
  }



}
