package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.litesockets.Server;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.WireProtocol;

/**
 * This is a {@link Server} implementation of a TCP Server.  {@link org.threadly.litesockets.Server.ClientAcceptor} calls by this server will
 * Create {@link TCPClient}.
 * 
 */
public class TCPServer extends Server {
  private final ServerSocketChannel socket;
  protected final Executor executor;
  protected final SocketExecuter sei;
  protected final AtomicBoolean closed = new AtomicBoolean(false);
  
  private volatile ClientAcceptor clientAcceptor;
  private volatile ServerCloser closer;

  /**
   * Creates a new TCP Listen socket on the passed host/port.  This is Listen port is created
   * immediately and will throw an exception if for any reason it can't be opened.
   * 
   * @param host The host address/interface to create this listen port on.
   * @param port The port to use for the listen port.
   * @throws IOException This is throw if for any reason we can't create the listen port.
   */
  public TCPServer(SocketExecuter se, String host, int port) throws IOException {
    this.sei = se;
    executor = se.getExecutorFor(this);
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
  public TCPServer(SocketExecuter se, ServerSocketChannel server) throws IOException{
    this.sei = se;
    executor = se.getExecutorFor(this);
    server.configureBlocking(false);
    socket = server;
  }

  @Override
  public SocketExecuter getSocketExecuter() {
    return this.sei;
  }

  @Override
  public ServerCloser getCloser() {
    return closer;
  }

  @Override
  public void setCloser(ServerCloser closer) {
    this.closer = closer;
  }

  @Override
  public ServerSocketChannel getSelectableChannel() {
    return socket;
  }

  @Override
  public void close() {
    if(closed.compareAndSet(false, true)) {
      sei.stopListening(this);
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
  public void acceptChannel(final SelectableChannel c) {
    if(executor != null ) {
      executor.execute(new Runnable() {
        public void run() {
          try {
            TCPClient client = sei.createTCPClient((SocketChannel)c);
            if(clientAcceptor != null) {
              clientAcceptor.accept(client);
            }
          } catch (IOException e) {
            //We dont care, client closed before we could do anything with it.
          }
        }
      });
    }
  }

  protected void callCloser() {
    if(executor != null && closer != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          getCloser().onClose(TCPServer.this);
        }});
    }
  }

  @Override
  public void start() {
    sei.startListening(this);
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }
}
