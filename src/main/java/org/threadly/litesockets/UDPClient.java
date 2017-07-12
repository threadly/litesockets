package org.threadly.litesockets;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.buffers.SimpleMergedByteBuffers;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.Clock;

/**
 *  A Client representation of a UDP connection.
 * 
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
  protected static final ListenableFuture<Boolean> COMPLETED_FUTURE = FutureUtils.immediateResultFuture(true);
  
  private final UDPSocketOptions uso = new UDPSocketOptions();
  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  protected final InetSocketAddress remoteAddress;
  protected final UDPServer udpServer;
  
  private volatile ListenableFuture<Long> lastWriteFuture = IOUtils.FINISHED_LONG_FUTURE;
  

  protected UDPClient(final InetSocketAddress sa, final UDPServer server) {
    super(server.getSocketExecuterCommonBase(), 
          server.getSocketExecuterCommonBase().getExecutorFor(sa));
    this.remoteAddress = sa;
    udpServer = server;
  }
  
  @Override
  protected void doSocketRead(boolean doLocal) {
  }

  @Override
  protected void doSocketWrite(boolean doLocal) {
  }
  
  @Override
  protected void setConnectionStatus(final Throwable t) {
    //UDP has no "connection"
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
  protected void addReadBuffer(final ByteBuffer bb) {
    addReadStats(bb.remaining());
    synchronized(readerLock) {
      readBuffers.add(bb);
    }
    callReader();
  }

  @Override
  protected SocketChannel getChannel() {
    return null;
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
  public boolean isClosed() {
    return this.closed.get();
  }

  @Override
  protected void close(Throwable error) {
    if(this.setClose()) {
      callClosers(error);
    }
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.UDP;
  }

  @Override
  public boolean canWrite() {
    return true;
  }

  @Override
  public int getWriteBufferSize() {
    return 0;
  }

  @Override
  public boolean hasConnectionTimedOut() {
    return false;
  }

  @Override
  public ListenableFuture<Boolean> connect() {
    return COMPLETED_FUTURE;
  }

  @Override
  public int getTimeout() {
    return 0;
  }

  @Override
  public InetSocketAddress getRemoteSocketAddress() {
    return remoteAddress;
  }

  @Override
  public InetSocketAddress getLocalSocketAddress() {
    return (InetSocketAddress)udpServer.getSelectableChannel().socket().getLocalSocketAddress();
  }

  @Override
  public String toString() {
    return "UDPClient:FROM:"+getLocalSocketAddress()+":TO:"+getRemoteSocketAddress();
  }

  @Override
  public ListenableFuture<?> write(final ByteBuffer bb) {
    return write(new SimpleMergedByteBuffers(false, bb));
  }
  
  @Override
  public ListenableFuture<?> write(final MergedByteBuffers mbb) {
    addWriteStats(mbb.remaining());
    if(!closed.get()) {
      lastWriteFuture = udpServer.write(mbb.pullBuffer(mbb.remaining()), remoteAddress);
      return lastWriteFuture;
    }
    return lastWriteFuture;
  }
  
  public ListenableFuture<?> lastWriteFuture() {
    return lastWriteFuture;
  }

  @Override
  public void setConnectionTimeout(final int timeout) {
    //No connection to Timeout
  }

  @Override
  public ClientOptions clientOptions() {
    return uso;
  }
  
  @Override
  public ReuseableMergedByteBuffers getRead() {
    ReuseableMergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    int start = 0;
    int finished = 0;
    synchronized(readerLock) {
      start = getReadBufferSize();
      mbb.add(readBuffers.popBuffer());
      finished = start - getReadBufferSize();
    }
    if(start >= maxBufferSize && finished < maxBufferSize) {
      se.setClientOperations(this);
    }
    return mbb;
  }

  /**
   * 
   * @author lwahlmeier
   *
   */
  private class UDPSocketOptions extends BaseClientOptions {
    
    @Override
    public boolean setSocketSendBuffer(int size) {
      int prev = getSocketSendBuffer();
      try {
        udpServer.getSelectableChannel().socket().setSendBufferSize(size);
        if(size != getSocketSendBuffer()) {
          udpServer.setFrameSize(prev);
          return false;
        }
        return true;
      } catch (SocketException e) {
        return false;
      }
    }

    @Override
    public int getSocketSendBuffer() {
      try {
        return udpServer.getSelectableChannel().socket().getSendBufferSize();
      } catch (SocketException e) {
        return -1;
      }
    }

    @Override
    public boolean setSocketRecvBuffer(int size) {
      int prev = getSocketRecvBuffer();
      try {
        udpServer.getSelectableChannel().socket().setReceiveBufferSize(size);
        if(size != getSocketRecvBuffer()) {
          udpServer.setFrameSize(prev);
          return false;
        }
        return true;
      } catch (SocketException e) {
        return false;
      }
    }

    @Override
    public int getSocketRecvBuffer() {
      try {
        return udpServer.getSelectableChannel().socket().getReceiveBufferSize();
      } catch (SocketException e) {
        return -1;
      }
    }

    @Override
    public boolean setUdpFrameSize(int size) {
      udpServer.setFrameSize(size);
      return true;
    }

    @Override
    public int getUdpFrameSize() {
      return udpServer.getFrameSize();
    }
  }

}
