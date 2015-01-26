package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;

import org.threadly.litesockets.Client;

/**
 * This is a generic Client for TCP connections.  This client can be either from the "client" side or
 * from a client from a TCP "Server", and both function the same way.
 * 
 * 
 *   
 *   
 * @author lwahlmeier
 *
 */
public class TCPClient extends Client {
 
  protected final SocketChannel channel;
  
  /**
   * This creates a connection to the specified port and IP.
   * 
   * @param host hostname or ip address to connect too.
   * @param port port on that host to try and connect too.
   * @throws IOException if for any reason a connection can not be made an IOException will throw with more details about why. 
   */
  public TCPClient(String host, int port) throws IOException {
    channel = SocketChannel.open(new InetSocketAddress(host, port));
    channel.configureBlocking(false);
  }
  
  /**
   * This creates a TCPClient based off an already existing SocketChannel.
   * 
   * @param channel the SocketChannel to use for this client.
   * @throws IOException if there is anything wrong with the SocketChannel this will throw.
   */
  public TCPClient(SocketChannel channel) throws IOException {
    if(! channel.isOpen()) {
      throw new ClosedChannelException();
    }
    if(channel.isBlocking()) {
      channel.configureBlocking(false);
    }
    this.channel = channel;
  }

  @Override
  public SocketChannel getChannel() {
    return channel;
  }

  @Override
  public Socket getSocket() {
    return channel.socket();
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if(closed.compareAndSet(false, true)) {
      try {
        ce.removeClient(this);
        channel.close();
      } catch (IOException e) {
        //we dont care
      } finally {
        callCloser();
      }
    }
  }
  
  @Override
  public void setReader(Reader reader) {
    super.setReader(reader);
  }
  
  @Override
  public Reader getReader() {
    return super.getReader();
  }
  
  @Override
  public void setCloser(Closer closer) {
    super.setCloser(closer);
  }
  
  @Override
  public Closer getCloser() {
    return super.getCloser();
  }
}
