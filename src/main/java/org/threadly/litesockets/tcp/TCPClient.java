package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.SocketExecuterInterface.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.Clock;

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
public class TCPClient implements Client {
  /**
   * The default SocketConnection time out (10 seconds)
   */
  public static final int DEFAULT_SOCKET_TIMEOUT = 10000;
  /**
   * Default max buffer size (64k).  Read and write buffers are independant of eachother.
   */
  public static final int DEFAULT_MAX_BUFFER_SIZE = 64*1024;
  /**
   * Minimum allowed readBuffer (4k).  If the readBuffer is lower then this we will create a new one.
   */
  public static final int MIN_READ_BUFFER_SIZE = 4*1024;
  /**
   * When we hit the minimum read buffer size we will create a new one of this size (64k).
   */
  public static final int NEW_READ_BUFFER_SIZE = 64*1024;
  

  private final MergedByteBuffers readBuffers = new MergedByteBuffers();
  private final MergedByteBuffers writeBuffers = new MergedByteBuffers();
  protected final String host;
  protected final int port;
  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  protected final int maxConnectionTime;
  protected final AtomicBoolean startedConnection = new AtomicBoolean(false);

  protected volatile SocketChannel channel;
  protected volatile int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
  protected volatile int minAllowedReadBuffer = MIN_READ_BUFFER_SIZE;
  protected volatile int newReadBuffer = NEW_READ_BUFFER_SIZE;
  
  protected volatile Closer closer;
  protected volatile Reader reader;
  protected volatile SubmitterExecutorInterface sei;
  protected volatile SocketExecuterInterface seb;
  protected volatile long connectExpiresAt = -1;
  protected SettableListenableFuture<Boolean> connectionFuture = new SettableListenableFuture<Boolean>();
  protected ClientByteStats stats = new ClientByteStats();
  protected AtomicBoolean closed = new AtomicBoolean(false);

  private ByteBuffer currentWriteBuffer;
  private ByteBuffer readByteBuffer = ByteBuffer.allocate(NEW_READ_BUFFER_SIZE);


  /**
   * This creates a connection to the specified port and IP.
   * 
   * @param host hostname or ip address to connect too.
   * @param port port on that host to try and connect too.
   */
  public TCPClient(String host, int port){
    this(host, port, DEFAULT_SOCKET_TIMEOUT);
  }

  /**
   * This creates a connection to the specified port and IP.
   * 
   * @param host hostname or ip address to connect too.
   * @param port port on that host to try and connect too.
   * @param timeout this is the max amount of time we will wait for a connection to be made (default is 10000 milliseconds).
   */
  public TCPClient(String host, int port, int timeout) {
    maxConnectionTime = timeout;
    this.host = host;
    this.port = port;
  }

  /**
   * This creates a TCPClient based off an already existing SocketChannel.
   * 
   * @param channel the SocketChannel to use for this client.
   * @throws IOException if there is anything wrong with the SocketChannel this will throw.
   */
  public TCPClient(SocketChannel channel) throws IOException {
    maxConnectionTime = DEFAULT_SOCKET_TIMEOUT;
    if(! channel.isOpen()) {
      throw new ClosedChannelException();
    }
    host = channel.socket().getInetAddress().getHostAddress();
    port = channel.socket().getPort();
    if(channel.isBlocking()) {
      channel.configureBlocking(false);
    }
    this.channel = channel;
    startedConnection.set(true);
  }
  
  @Override
  public ListenableFuture<Boolean> connect() throws IOException {
    if(startedConnection.compareAndSet(false, true)) {
      connectionFuture = new SettableListenableFuture<Boolean>();
      channel = SocketChannel.open();
      channel.configureBlocking(false);
      channel.connect(new InetSocketAddress(host, port));
      connectExpiresAt = maxConnectionTime + Clock.lastKnownForwardProgressingMillis(); 
    }
    return connectionFuture;
  }
  
  @Override
  public void setConnectionStatus(Throwable t) {
    if(t != null) {
      connectionFuture.setFailure(t);
    }
    connectionFuture.setResult(true);
  }
  

  @Override
  public boolean hasConnectionTimedOut() {
    if(! startedConnection.get()) {
      return false;
    }
    return connectExpiresAt < Clock.lastKnownForwardProgressingMillis(); 
  }
  

  @Override
  public int getTimeout() {
    return maxConnectionTime;
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
        if(seb != null) {
          seb.removeClient(this);
        }
        channel.close();
      } catch (IOException e) {
        //we dont care
      } finally {
        final Closer lcloser = closer;
        if(lcloser != null && this.sei != null) {
          sei.execute(new Runnable() {
            @Override
            public void run() {
              lcloser.onClose(TCPClient.this);
            }});
        }
      }
    }
  }

  @Override
  public void setReader(Reader reader) {
    if(! closed.get()) {
      this.reader = reader;
    }
  }


  @Override
  public Reader getReader() {
    return reader;
  }

  @Override
  public void setCloser(Closer closer) {
    if(! closed.get()) {
      this.closer = closer;
    }
  }

  @Override
  public Closer getCloser() {
    return closer;
  }

  /**
   * This is used by SSLClient to close the TCPClient object w/o closing its socket.
   * We need to do this to make the TCPClient unusable.
   */
  public void markClosed() {
    this.closed.set(true);
    if(seb != null) {
      this.seb.removeClient(this);
    }
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
  public boolean canRead() {
    if(readBuffers.remaining() > maxBufferSize) {
      return false;
    } 
    return true;
  }

  @Override
  public boolean canWrite() {
    if(writeBuffers.remaining() > 0) {
      return true;
    }
    return false;
  }

  @Override
  public ByteBuffer provideEmptyReadBuffer() {
    if(readByteBuffer.remaining() < minAllowedReadBuffer) {
      readByteBuffer = ByteBuffer.allocate(maxBufferSize*2);
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
  public SubmitterExecutorInterface getClientsThreadExecutor() {
    return this.sei;
  }
  
  @Override
  public void setClientsThreadExecutor(SubmitterExecutorInterface cte) {
    if(cte != null) {
      this.sei = cte;
    }
  }

  @Override
  public SocketExecuterInterface getClientsSocketExecuter() {
    return seb;
  }
  
  @Override
  public void setClientsSocketExecuter(SocketExecuterInterface seb) {
    if(seb != null) {
      this.seb = seb;
    }
  }

  @Override
  public void setMaxBufferSize(int size) {
    if(size > 0) {
      maxBufferSize = size;
      if(this.seb != null) {
        this.seb.flagNewRead(this);
        synchronized (writeBuffers) {
          writeBuffers.notifyAll();
        }
      }
    } else {
      throw new IllegalArgumentException("Default size must be more then 0");
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
    }
    if(mbb.remaining() >= maxBufferSize) {
      seb.flagNewRead(this);
    }
    return mbb;
  }

  @Override
  public void addReadBuffer(ByteBuffer bb) {
    stats.addRead(bb.remaining());
    final Reader lreader = reader;
    if(lreader != null) {
      synchronized(readBuffers) {
        int start = readBuffers.remaining();
        readBuffers.add(bb);
        if(start == 0){
          sei.execute(new Runnable() {
            @Override
            public void run() {
              lreader.onRead(TCPClient.this);
            }});
        }
      }
    }
  }

  @Override
  public boolean writeTry(ByteBuffer bb) {
    if(bb.hasRemaining()) {
      synchronized(writeBuffers) {
        if(getWriteBufferSize() < getMaxBufferSize()) {
          writeForce(bb);
          return true;
        } else {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public void writeBlocking(ByteBuffer bb) throws InterruptedException {
    if (bb.hasRemaining()) {
      synchronized (writeBuffers) {
        while(! writeTry(bb) && ! isClosed()) {
          writeBuffers.wait(1000);
        }
      }
    }
  }

  @Override
  public void writeForce(ByteBuffer bb) {
    synchronized(writeBuffers) {
      boolean needNotify = ! canWrite();
      writeBuffers.add(bb.slice());
      if(needNotify && seb != null && channel.isConnected()) {
        seb.flagNewWrite(this);
      }
    }
  }

  @Override
  public ByteBuffer getWriteBuffer() {
    if(currentWriteBuffer != null && currentWriteBuffer.remaining() == 0) {
      return currentWriteBuffer;
    }
    synchronized(writeBuffers) {
      //This is to keep from doing a ton of little writes if we can.  We will try to 
      //do at least 8k at a time, and up to 65k if we are already having to combine buffers
      if(writeBuffers.nextPopSize() < 65536/8 && writeBuffers.remaining() > writeBuffers.nextPopSize()) {
        if(writeBuffers.remaining() < 65536) {
          currentWriteBuffer = writeBuffers.pull(writeBuffers.remaining());
        } else {
          currentWriteBuffer = writeBuffers.pull(65536);
        }
      } else {
        currentWriteBuffer = writeBuffers.pop();
      }
    }
    return currentWriteBuffer;
  }


  @Override
  public void reduceWrite(int size) {
    synchronized(writeBuffers) {
      stats.addWrite(size);
      if(! currentWriteBuffer.hasRemaining()) {
        currentWriteBuffer = null;
      }
      writeBuffers.notifyAll();
    }
  }
  
  private static class ClientByteStats extends SimpleByteStats {
    public ClientByteStats() {
      super();
    }

    @Override
    protected void addWrite(int size) {
      if(size < 0) {
        throw new IllegalArgumentException("Size must be positive number");
      }
      super.addWrite(size);
    }
    
    @Override
    protected void addRead(int size) {
      if(size < 0) {
        throw new IllegalArgumentException("Size must be positive number");
      }
      super.addRead(size);
    }
  }

}
