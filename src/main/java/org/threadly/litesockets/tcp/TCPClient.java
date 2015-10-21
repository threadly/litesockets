package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;
import org.threadly.util.Pair;

/**
 * This is a generic Client for TCP connections.  This client can be either from the "client" side or
 * from a client from a {@link TCPServer}, and both function the same way.
 *   
 */
public class TCPClient extends Client {
  /**
   * The default SocketConnection time out (10 seconds).
   */
  public static final int DEFAULT_SOCKET_TIMEOUT = 10000;
  /**
   * Default max buffer size (64k).  Read and write buffers are independent of each other.
   */
  public static final int DEFAULT_MAX_BUFFER_SIZE = 65536;
  /**
   * Minimum allowed readBuffer (4k).  If the readBuffer is lower then this we will create a new one.
   */
  public static final int MIN_READ_BUFFER_SIZE = 4096;
  /**
   * When we hit the minimum read buffer size we will create a new one of this size (64k).
   */
  public static final int NEW_READ_BUFFER_SIZE = 65536;
  
  public static final int MIN_WRITE_BUFFER_SIZE = 8192;
  public static final int MAX_COMBINED_WRITE_BUFFER_SIZE = 65536;
  

  private final MergedByteBuffers readBuffers = new MergedByteBuffers();
  private final MergedByteBuffers writeBuffers = new MergedByteBuffers();
  private final Deque<Pair<Long, SettableListenableFuture<Long>>> writeFutures = new ArrayDeque<Pair<Long, SettableListenableFuture<Long>>>();
  protected final String host;
  protected final int port;
  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  protected final AtomicBoolean startedConnection = new AtomicBoolean(false);
  protected final SettableListenableFuture<Boolean> connectionFuture = new SettableListenableFuture<Boolean>(false);
  protected final SocketExecuter sei;
  protected final Executor cexec;
  protected final SocketChannel channel;

  protected volatile int maxConnectionTime = DEFAULT_SOCKET_TIMEOUT;
  protected volatile int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
  protected volatile int minAllowedReadBuffer = MIN_READ_BUFFER_SIZE;
  protected volatile int newReadBuffer = NEW_READ_BUFFER_SIZE;
  
  protected volatile Closer closer;
  protected volatile Reader reader;
  protected volatile long connectExpiresAt = -1;
  protected ClientByteStats stats = new ClientByteStats();
  protected AtomicBoolean closed = new AtomicBoolean(false);
  protected final InetSocketAddress remoteAddress;
  private volatile ByteBuffer currentWriteBuffer = ByteBuffer.allocate(0);
  private ByteBuffer readByteBuffer = ByteBuffer.allocate(NEW_READ_BUFFER_SIZE);
  private volatile SSLProcessor sslProcessor;

  /**
   * This creates TCPClient with a connection to the specified port and IP.  This connection is not is not
   * yet made {@link #connect()} must be called which will do the actual connect.
   * 
   * @param sei The {@link SocketExecuter} implementation this client will use.
   * @param host The hostname or IP address to connect this client too.
   * @param port The port to connect this client too.
   * @throws IOException - This is thrown if there are any problems making the socket.
   */
  public TCPClient(SocketExecuter sei, String host, int port) throws IOException {
    this.sei = sei;
    this.host = host;
    this.port = port;
    this.channel = SocketChannel.open();
    channel.configureBlocking(false);
    cexec = sei.getExecutorFor(this);
    remoteAddress = new InetSocketAddress(host, port);
  }

  /**
   * <p>This creates a TCPClient based off an already existing {@link SocketChannel}.  
   * This {@link SocketChannel} must already be connected.</p>
   * 
   * @param sei the {@link SocketExecuter} to use for this client.
   * @param channel the {@link SocketChannel} to use for this client.
   * @throws IOException if there is anything wrong with the {@link SocketChannel} this will be thrown.
   */
  public TCPClient(SocketExecuter sei, SocketChannel channel) throws IOException {
    this.sei = sei;
    cexec = sei.getExecutorFor(this);
    if(! channel.isOpen()) {
      throw new ClosedChannelException();
    }
    connectionFuture.setResult(true);
    host = channel.socket().getInetAddress().getHostAddress();
    port = channel.socket().getPort();
    if(channel.isBlocking()) {
      channel.configureBlocking(false);
    }
    this.channel = channel;
    remoteAddress = (InetSocketAddress) channel.socket().getRemoteSocketAddress();
    startedConnection.set(true);
  }
  
  @Override
  public void setConnectionTimeout(int timeout) {
    ArgumentVerifier.assertNotNegative(timeout, "Timeout");
    this.maxConnectionTime = timeout;
  }
  
  @Override
  public ListenableFuture<Boolean> connect(){
    if(startedConnection.compareAndSet(false, true)) {
      try {
        channel.connect(new InetSocketAddress(host, port));
        connectExpiresAt = maxConnectionTime + Clock.lastKnownForwardProgressingMillis();
        sei.setClientOperations(this);
        sei.watchFuture(connectionFuture, maxConnectionTime);
      } catch (Exception e) {
        connectionFuture.setFailure(e);
        close();
      }
    }
    return connectionFuture;
  }
  
  @Override
  protected void setConnectionStatus(Throwable t) {
    if(t != null) {
      if(connectionFuture.setFailure(t)) {
        close();
      }
    } else {
      connectionFuture.setResult(true);
    }
  }
  
  @Override
  public boolean hasConnectionTimedOut() {
    if(! startedConnection.get()) {
      return false;
    }
    if(channel.isConnected()) {
      return false;
    }
    return Clock.lastKnownForwardProgressingMillis() > connectExpiresAt; 
  }
  
  @Override
  public int getTimeout() {
    return maxConnectionTime;
  }

  @Override
  protected SocketChannel getChannel() {
    return channel;
  }

  @Override
  protected Socket getSocket() {
    return channel.socket();
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if(closed.compareAndSet(false, true)) {
      sei.setClientOperations(this);
      for(Pair<Long, SettableListenableFuture<Long>> p: this.writeFutures) {
        p.getRight().setFailure(new ClosedChannelException());
      }
      synchronized(writeBuffers) {
        writeFutures.clear();
        writeBuffers.discard(this.writeBuffers.remaining());
      }
      try {
        channel.close();
      } catch (IOException e) {
        //we dont care
      } finally {
        final Closer lcloser = closer;
        if(lcloser != null && this.cexec != null) {
          cexec.execute(new Runnable() {
            @Override
            public void run() {
              lcloser.onClose(TCPClient.this);
            }});
        }
      }
    }
  }

  @Override
  public void setReader(final Reader reader) {
    if(! closed.get()) {
      this.reader = reader;
      synchronized(readBuffers) {
        if(this.readBuffers.remaining() > 0) {
          cexec.execute(new Runnable() {
            @Override
            public void run() {
              reader.onRead(TCPClient.this);
            }});
        }
      }
    }
  }

  @Override
  public Reader getReader() {
    return reader;
  }

  @Override
  public void setCloser(final Closer closer) {
    if(! closed.get()) {
      this.closer = closer;
    } else {
      cexec.execute(new Runnable() {
        @Override
        public void run() {
          closer.onClose(TCPClient.this);
        }});
    }
  }

  @Override
  public Closer getCloser() {
    return closer;
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.TCP;
  }

  @Override
  public SimpleByteStats getStats() {
    return stats;
  }

  @Override
  protected boolean canRead() {
    if(readBuffers.remaining() > maxBufferSize) {
      return false;
    } 
    return true;
  }

  @Override
  protected boolean canWrite() {
    return writeBuffers.remaining() > 0 ;
  }

  @Override
  protected ByteBuffer provideReadByteBuffer() {
    if(readByteBuffer.remaining() < minAllowedReadBuffer) {
      readByteBuffer = ByteBuffer.allocate(DEFAULT_MAX_BUFFER_SIZE);
    }
    return readByteBuffer;
  }

  @Override
  public int getReadBufferSize() {
    return this.readBuffers.remaining();
  }

  @Override
  public int getWriteBufferSize() {
    return this.writeBuffers.remaining();
  }

  @Override
  public int getMaxBufferSize() {
    return this.maxBufferSize;
  }

  @Override
  public Executor getClientsThreadExecutor() {
    return this.cexec;
  }

  @Override
  public SocketExecuter getClientsSocketExecuter() {
    return sei;
  }

  @Override
  public void setMaxBufferSize(int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    maxBufferSize = size;
    if(channel.isConnected()) {
      this.sei.setClientOperations(this);
      synchronized (writeBuffers) {
        writeBuffers.notifyAll();
      }
    }
  }

  @Override
  public MergedByteBuffers getRead() {
    MergedByteBuffers mbb = null;
    synchronized(readBuffers) {
      if(readBuffers.remaining() == 0) {
        return null;
      }
      mbb = readBuffers.duplicateAndClean();
      if(sslProcessor != null && sslProcessor.handShakeStarted()) {
        mbb = sslProcessor.doRead(mbb);
      }
    }
    if(mbb.remaining() >= maxBufferSize) {
      sei.setClientOperations(this);
    }
    return mbb;
  }

  @Override
  protected void addReadBuffer(ByteBuffer bb) {
    stats.addRead(bb.remaining());
    synchronized(readBuffers) {
      int start = readBuffers.remaining();
      final Reader lreader = reader;
      readBuffers.add(bb);
      if(readBuffers.remaining() > 0 && start == 0 && lreader != null){
        cexec.execute(new Runnable() {
          @Override
          public void run() {
            lreader.onRead(TCPClient.this);
          }});
      }
    }
  }

  @Override
  public ListenableFuture<?> write(ByteBuffer bb) {
    if(isClosed()) {
      throw new IllegalStateException("Cannot write to closed client!");
    }
    synchronized(writeBuffers) {
      boolean needNotify = ! canWrite();
      SettableListenableFuture<Long> slf = new SettableListenableFuture<Long>();
      if(sslProcessor != null && sslProcessor.handShakeStarted()) {
        writeBuffers.add(sslProcessor.write(bb));
      } else {
        writeBuffers.add(bb);
      }
      this.writeFutures.add(new Pair<Long, SettableListenableFuture<Long>>(writeBuffers.getTotalConsumedBytes()+writeBuffers.remaining(), slf));
      if(needNotify && sei != null && channel.isConnected()) {
        sei.setClientOperations(this);
      }
      
      return slf;
    }
  }

  @Override
  protected ByteBuffer getWriteBuffer() {
    if(currentWriteBuffer.remaining() != 0) {
      return currentWriteBuffer;
    }
    synchronized(writeBuffers) {
      //This is to keep from doing a ton of little writes if we can.  We will try to 
      //do at least 8k at a time, and up to 65k if we are already having to combine buffers
      if(writeBuffers.nextPopSize() < MIN_WRITE_BUFFER_SIZE && writeBuffers.remaining() > writeBuffers.nextPopSize()) {
        if(writeBuffers.remaining() < MAX_COMBINED_WRITE_BUFFER_SIZE) {
          currentWriteBuffer = writeBuffers.pull(writeBuffers.remaining());
        } else {
          currentWriteBuffer = writeBuffers.pull(MAX_COMBINED_WRITE_BUFFER_SIZE);
        }
      } else {
        currentWriteBuffer = writeBuffers.pop();
      }
    }
    return currentWriteBuffer;
  }

  @Override
  protected void reduceWrite(int size) {
    synchronized(writeBuffers) {
      stats.addWrite(size);
      if(currentWriteBuffer.remaining() == 0) {
        while(this.writeFutures.peekFirst() != null && writeFutures.peekFirst().getLeft() <= writeBuffers.getTotalConsumedBytes()) {
          Pair<Long, SettableListenableFuture<Long>> p = writeFutures.pollFirst();
          if(!p.getRight().isDone()) {
            p.getRight().setResult(p.getLeft());
          }
        }
      }
    }
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
  public InetSocketAddress getRemoteSocketAddress() {
    return remoteAddress;
  }

  @Override
  public InetSocketAddress getLocalSocketAddress() {
    if(this.channel != null) {
      return (InetSocketAddress) channel.socket().getLocalSocketAddress();
    }
    return null;
  }
  
  @Override
  public String toString() {
    return "TCPClient:FROM:"+getLocalSocketAddress()+":TO:"+getRemoteSocketAddress();
  }

  @Override
  public boolean setSocketOption(SocketOption so, int value) {
    try{
      switch(so) {
      case TCP_NODELAY: {
          this.channel.setOption(StandardSocketOptions.TCP_NODELAY, value==1);
          return true;
      }
      case SEND_BUFFER_SIZE: {
        this.channel.setOption(StandardSocketOptions.SO_SNDBUF, value);
        return true;
      }
      case RECV_BUFFER_SIZE: {
        this.channel.setOption(StandardSocketOptions.SO_RCVBUF, value);
        return true;
      }
      default:
        return false;
      }
    } catch(Exception e) {

    }
    return false;
  }
  
  public void setSSLEngine(SSLEngine ssle) {
    sslProcessor = new SSLProcessor(this, ssle);
  }
  
  public boolean isEncrypted() {
    if(sslProcessor == null) {
      return false;
    }
    return sslProcessor.isEncrypted();
  }
  
  public ListenableFuture<SSLSession> startSSL() {
    if(sslProcessor != null) {
      ListenableFuture<SSLSession> lf = sslProcessor.doHandShake();
      return lf;
    }
    throw new IllegalStateException("Must Set the SSLEngine before starting Encryption!");
  }
}
