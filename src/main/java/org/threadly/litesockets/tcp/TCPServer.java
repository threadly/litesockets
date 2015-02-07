package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.threadly.litesockets.Server;
import org.threadly.litesockets.SocketExecuterBase.WireProtocol;

public class TCPServer extends Server {
  private final ServerSocketChannel socket;
  private volatile ClientAcceptor clientAcceptor;
  
  public TCPServer(String host, int port) throws IOException {
    socket = ServerSocketChannel.open();
    socket.socket().bind(new InetSocketAddress(host, port), 100);
    socket.configureBlocking(false);
  }
  
  public TCPServer(ServerSocketChannel server) throws IOException{
    server.configureBlocking(false);
    socket = server;
  }

  @Override
  public ServerSocketChannel getSelectableChannel() {
    return socket;
  }

  @Override
  public void close() {
    if(closed.compareAndSet(false, true)) {
      try {
        socket.close();
      } catch (IOException e) {
        //We dont care
      } finally {
        callCloser();
      }
    }
  }

  @Override
  public WireProtocol getServerType() {
    return WireProtocol.TCP;
  }

  @Override
  public ClientAcceptor getClientAcceptor() {
    return this.clientAcceptor;
  }
  
  @Override
  public void setClientAcceptor(ClientAcceptor acceptor) {
    clientAcceptor = acceptor;
  }

  @Override
  public void accept(SelectableChannel c) {
    try {
      TCPClient client = new TCPClient((SocketChannel)c);
      if(clientAcceptor != null) {
        clientAcceptor.accept(client);
      }
    } catch (IOException e) {
      //We dont care, client closed before we could do anything with it.
    }
  }
}
