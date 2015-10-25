package org.threadly.litesockets.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.WireProtocol;
import org.threadly.util.Clock;

/**
 *  This is the UDPClient for litesockets.  The UDPClient is a little special as it is
 *  not actually a selectable client.  The Server actually does all the "Reading" for the socket. 
 *  
 *  Since all UDP connections must has a bound port all UDPClients in litesockets are tight to a UDPServer.
 *  The UDPClient is basically a unique host that is sending messages to the open UDP socket.
 *  
 *  Another unique aspect to UDPClients is there writing.  When any form of "write" is called it is immediately done
 *  on the socket.
 *  
 */
public class UDPClient extends Client {

  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  protected final InetSocketAddress remoteAddress;
  protected final UDPServer udpServer;

  protected UDPClient(final InetSocketAddress sa, final UDPServer server) {
    super(server.getSocketExecuter());
    this.remoteAddress = sa;
    udpServer = server;
  }

  @Override
  public boolean equals(final Object o) {
    if(o instanceof UDPClient && hashCode() == o.hashCode()) {
      final UDPClient u = (UDPClient)o;
      if(u.remoteAddress.equals(this.remoteAddress) && u.udpServer.getSelectableChannel().equals(udpServer.getSelectableChannel())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int hashCode() {
    return remoteAddress.hashCode() * udpServer.getSelectableChannel().hashCode();
  }

  @Override
  protected SocketChannel getChannel() {
    return null;
  }

  @Override
  protected Socket getSocket() {
    return null;
  }

  @Override
  public boolean isClosed() {
    return this.closed.get();
  }

  @Override
  public void close() {
    if(this.setClose()) {
      callClosers();
    }
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.UDP;
  }

  @Override
  protected void addReadBuffer(final ByteBuffer bb) {
    super.addReadBuffer(bb);
  }

  @Override
  protected boolean canWrite() {
    return true;
  }

  @Override
  public int getWriteBufferSize() {
    return 0;
  }

  @Override
  protected ByteBuffer getWriteBuffer() {
    return null;
  }

  @Override
  protected void reduceWrite(final int size) {
    //UDPClient does not have pending writes to reduce
  }

  @Override
  public boolean hasConnectionTimedOut() {
    return false;
  }

  @Override
  public ListenableFuture<Boolean> connect() {
    return FutureUtils.immediateResultFuture(true);
  }

  @Override
  public int getTimeout() {
    return 0;
  }

  @Override
  protected void setConnectionStatus(final Throwable t) {
    //UDP has no "connection"
  }

  @Override
  public InetSocketAddress getRemoteSocketAddress() {
    return remoteAddress;
  }

  @Override
  public InetSocketAddress getLocalSocketAddress() {
    if(udpServer.getSelectableChannel() != null) {
      return (InetSocketAddress)udpServer.getSelectableChannel().socket().getLocalSocketAddress();
    }
    return null;
  }

  @Override
  public String toString() {
    return "UDPClient:FROM:"+getLocalSocketAddress()+":TO:"+getRemoteSocketAddress();
  }

  @Override
  public ListenableFuture<?> write(final ByteBuffer bb) {
    addWriteStats(bb.remaining());
    if(!closed.get()) {
      try {
        udpServer.channel.send(bb, remoteAddress);
      } catch (IOException e) {
      }
    }
    return FutureUtils.immediateResultFuture(true);
  }

  @Override
  public void setConnectionTimeout(final int timeout) {
    //No connection to Timeout
  }

  @Override
  public boolean setSocketOption(final SocketOption so, final int value) {
    try{
      if(so == SocketOption.UDP_FRAME_SIZE) {
        this.udpServer.setFrameSize(value);
        return true;        
      }
    } catch(Exception e) {

    }
    return false;
  }
}
