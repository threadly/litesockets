package org.threadly.litesockets.udp;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.SocketExecuterInterface.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.ArgumentVerifier;
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
@SuppressWarnings("deprecation")
public class UDPClient extends Client {
  public static final int DEFAULT_MAX_BUFFER_SIZE = 64*1024;
  public static final int MIN_READ= 4*1024;

  protected ClientByteStats stats = new ClientByteStats();

  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  private final MergedByteBuffers readBuffers = new MergedByteBuffers();
  protected final SocketAddress remoteAddress;
  protected final UDPServer udpServer;

  protected int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
  protected int minAllowedReadBuffer = MIN_READ;

  protected volatile Closer closer;
  protected volatile Reader reader;
  protected volatile Executor executer;
  protected volatile SocketExecuterInterface sei;
  protected AtomicBoolean closed = new AtomicBoolean(false);

  
  protected UDPClient(SocketAddress sa, UDPServer server) {
    this.remoteAddress = sa;
    udpServer = server;
  }
  
  @Override
  public boolean equals(Object o) {
    if(o instanceof UDPClient) {
      if(hashCode() == o.hashCode()) {
        UDPClient u = (UDPClient)o;
        if(u.remoteAddress.equals(this.remoteAddress) && u.udpServer.getSelectableChannel().equals(udpServer.getSelectableChannel())) {
          return true;
        }
      }
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    return remoteAddress.hashCode() * udpServer.getSelectableChannel().hashCode();
  }
  
  @Override
  protected void setClientsThreadExecutor(Executor executer) {
    if(executer != null) {
      this.executer = executer;
    }
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
    if(closed.compareAndSet(false, true)) {
      final Closer lcloser = closer;
      if(lcloser != null && this.sei != null) {
        executer.execute(new Runnable() {
          @Override
          public void run() {
            lcloser.onClose(UDPClient.this);
          }});
      }
    }
  }
  
  @Override
  protected void addReadBuffer(ByteBuffer bb) {
    stats.addRead(bb.remaining());
    final Reader lreader = reader;
    if(lreader != null) {
      synchronized(readBuffers) {
        int start = readBuffers.remaining();
        readBuffers.add(bb);
        if(start == 0){
          executer.execute(new Runnable() {
            @Override
            public void run() {
              lreader.onRead(UDPClient.this);
            }});
        }
      }
    }
  }
  
  @Override
  public void writeForce(ByteBuffer bb) {
    write(bb);
  }
  
  @Override
  public boolean writeTry(ByteBuffer bb) {
    if(!this.closed.get()) {
      write(bb);
      return true;
    }
    return false;
  }
  
  @Override
  public void writeBlocking(ByteBuffer bb) {
    write(bb);
  }
  
  @Override
  public void setReader(Reader reader) {
    this.reader = reader;
  }
  
  @Override
  public Reader getReader() {
    return reader;
  }
  
  @Override
  public void setCloser(Closer closer) {
    this.closer = closer;
  }
  
  @Override
  public Closer getCloser() {
    return closer;
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.UDP;
  }

  @Override
  public SimpleByteStats getStats() {
    return stats;
  }

  @Override
  protected boolean canRead() {
    return true;
  }

  @Override
  protected boolean canWrite() {
    return true;
  }

  @Override
  protected ByteBuffer provideReadByteBuffer() {
    //All Reads happen on the UDP Server
    return null;
  }

  @Override
  public int getReadBufferSize() {
    return this.readBuffers.remaining();
  }

  @Override
  public int getWriteBufferSize() {
    return 0;
  }

  @Override
  public int getMaxBufferSize() {
    return this.maxBufferSize;
  }

  @Override
  public Executor getClientsThreadExecutor() {
    return executer;
  }

  @Override
  public SocketExecuterInterface getClientsSocketExecuter() {
    return sei;
  }

  @Override
  protected void setClientsSocketExecuter(SocketExecuterInterface sei) {
    if(sei != null) {
      this.sei = sei;
    }
  }

  @Override
  public void setMaxBufferSize(int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    maxBufferSize = size;
  }

  @Override
  public MergedByteBuffers getRead() {
    MergedByteBuffers mbb = null;
    synchronized(readBuffers) {
      if(readBuffers.remaining() == 0) {
        return null;
      }
      mbb = readBuffers.duplicateAndClean();
    }
    return mbb;
  }

  @Override
  protected ByteBuffer getWriteBuffer() {
    return null;
  }

  @Override
  protected void reduceWrite(int size) {
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
  protected void setConnectionStatus(Throwable t) {
  }
  
  @Override
  public SocketAddress getRemoteSocketAddress() {
    return remoteAddress;
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    if(this.udpServer.channel != null) {
      return udpServer.channel.socket().getLocalSocketAddress();
    }
    return null;
  }
  
  @Override
  public String toString() {
    return "UDPClient:FROM:"+getLocalSocketAddress()+":TO:"+getRemoteSocketAddress();
  }

  /**
   * Implementation of the SimpleByteStats.
   */
  private static class ClientByteStats extends SimpleByteStats {
    public ClientByteStats() {
      super();
    }

    @Override
    protected void addWrite(int size) {
      ArgumentVerifier.assertNotNegative(size, "size");
      super.addWrite(size);
    }
    
    @Override
    protected void addRead(int size) {
      ArgumentVerifier.assertNotNegative(size, "size");
      super.addRead(size);
    }
  }

  @Override
  public ListenableFuture<?> write(ByteBuffer bb) {
    stats.addWrite(bb.remaining());
    if(!closed.get()) {
      try {
        udpServer.channel.send(bb, remoteAddress);
      } catch (IOException e) {
      }
    }
    return FutureUtils.immediateResultFuture(true);
  }
}
