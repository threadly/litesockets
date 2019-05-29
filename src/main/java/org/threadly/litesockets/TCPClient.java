package org.threadly.litesockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.buffers.SimpleMergedByteBuffers;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.litesockets.utils.SSLProcessor;
import org.threadly.litesockets.utils.SSLProcessor.EncryptionException;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;
import org.threadly.util.Pair;


/**
 * A Simple TCP client.
 *   
 */
public class TCPClient extends Client {
  protected static final int DEFAULT_SOCKET_TIMEOUT = 10000;
  protected static final int MIN_WRITE_BUFFER_SIZE = 8192;
  protected static final int MAX_COMBINED_WRITE_BUFFER_SIZE = 65536;

  private final ReuseableMergedByteBuffers writeBuffers = new ReuseableMergedByteBuffers();
  private final Deque<Pair<Long, SettableListenableFuture<Long>>> writeFutures = new ArrayDeque<>(8);
  private final TCPSocketOptions tso = new TCPSocketOptions();
  protected final Object writerLock = new Object();
  protected final AtomicBoolean startedConnection = new AtomicBoolean(false);
  protected final SettableListenableFuture<Boolean> connectionFuture;
  protected final SocketChannel channel;
  protected final InetSocketAddress remoteAddress;

  private volatile ListenableFuture<Long> lastWriteFuture = IOUtils.FINISHED_LONG_FUTURE;
  private volatile ByteBuffer currentWriteBuffer = IOUtils.EMPTY_BYTEBUFFER;
  private volatile SSLProcessor sslProcessor;

  protected volatile int maxConnectionTime = DEFAULT_SOCKET_TIMEOUT;
  protected volatile long connectExpiresAt = -1;

  /**
   * This creates TCPClient with a connection to the specified port and IP.  This connection is not is not
   * yet made {@link #connect()} must be called which will do the actual connect.
   * 
   * @param sei The {@link SocketExecuter} implementation this client will use.
   * @param host The hostname or IP address to connect this client too.
   * @param port The port to connect this client too.
   * @throws IOException - This is thrown if there are any problems making the socket.
   */
  protected TCPClient(final SocketExecuterCommonBase sei, final String host, final int port, 
                      final boolean statsEnabled) throws IOException {
    super(sei, statsEnabled);
    
    connectionFuture = makeClientSettableListenableFuture();
    remoteAddress = new InetSocketAddress(host, port);
    channel = SocketChannel.open();
    channel.configureBlocking(false);
  }

  /**
   * <p>This creates a TCPClient based off an already existing {@link SocketChannel}.  
   * This {@link SocketChannel} must already be connected.</p>
   * 
   * @param sei the {@link SocketExecuter} to use for this client.
   * @param channel the {@link SocketChannel} to use for this client.
   * @throws IOException if there is anything wrong with the {@link SocketChannel} this will be thrown.
   */
  protected TCPClient(final SocketExecuterCommonBase sei, final SocketChannel channel, 
                      final boolean statsEnabled) throws IOException {
    super(sei, statsEnabled);
    
    if(! channel.isOpen()) {
      throw new ClosedChannelException();
    }
    connectionFuture = makeClientSettableListenableFuture();
    connectionFuture.setResult(true);
    if(channel.isBlocking()) {
      channel.configureBlocking(false);
    }
    this.channel = channel;
    remoteAddress = (InetSocketAddress) channel.socket().getRemoteSocketAddress();
    startedConnection.set(true);
  }

  @Override
  public void setConnectionTimeout(final int timeout) {
    ArgumentVerifier.assertGreaterThanZero(timeout, "Timeout");
    this.maxConnectionTime = timeout;
  }

  @Override
  public ListenableFuture<Boolean> connect(){
    if(startedConnection.compareAndSet(false, true)) {
      try {
        channel.connect(remoteAddress);
        connectExpiresAt = maxConnectionTime + Clock.accurateForwardProgressingMillis();
        se.setClientOperations(this);
        se.watchFuture(connectionFuture, maxConnectionTime);
      } catch (Exception e) {
        connectionFuture.setFailure(e);
        close();
      }
    }
    return connectionFuture;
  }

  @Override
  protected void setConnectionStatus(final Throwable t) {
    if(t == null) {
      connectionFuture.setResult(true);
    } else {
      if(connectionFuture.setFailure(t)) {
        close();
      }
    }
  }

  @Override
  public boolean hasConnectionTimedOut() {
    if(! startedConnection.get() || channel.isConnected()) {
      return false;
    }
    return Clock.lastKnownForwardProgressingMillis() > connectExpiresAt || 
        Clock.accurateForwardProgressingMillis() > connectExpiresAt; 
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
  public void close(Throwable error) {
    if(setClose()) {
      se.setClientOperations(this);
      this.getClientsThreadExecutor().execute(() -> {
        try {
          synchronized(writerLock) {
            if(writeFutures.size() > 0) {
              final ClosedChannelException cce = new ClosedChannelException();
              for(final Pair<Long, SettableListenableFuture<Long>> p: writeFutures) {
                p.getRight().setFailure(cce);
              }
            }
            writeFutures.clear();
            writeBuffers.discard(writeBuffers.remaining());
          }
          connectionFuture.setFailure(error);
          if(sslProcessor != null) {
            sslProcessor.failHandshake(error);
          }
        } finally {
          callClosers(true, error);
        }
      });
    }
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.TCP;
  }

  @Override
  public boolean canWrite() {
    return writeBuffers.remaining() + this.currentWriteBuffer.remaining() > 0 ;
  }

  @Override
  public int getWriteBufferSize() {
    return this.writeBuffers.remaining() + this.currentWriteBuffer.remaining();
  }

  @Override
  public ReuseableMergedByteBuffers getRead() {
    ReuseableMergedByteBuffers mbb = super.getRead();
    if(sslProcessor != null && sslProcessor.handShakeStarted() && mbb.remaining() > 0) {
      try {
        mbb = sslProcessor.decrypt(mbb);
      } catch(EncryptionException e) {
        this.close(e);
        return new ReuseableMergedByteBuffers(false);
      }
    }
    return mbb;
  }

  @Override
  protected void doSocketRead(boolean doLocal) {
    if(doLocal) {
      doClientRead(doLocal);
    } else {
      this.getClientsThreadExecutor().execute(()->doClientRead(doLocal));
    }
  }

  @Override
  protected void doSocketWrite(boolean doLocal) {
    if(doLocal) {
      doClientWrite(doLocal);
    } else {
      this.getClientsThreadExecutor().execute(()->doClientWrite(doLocal));
    }
  }

  @Override
  public ListenableFuture<?> write(final ByteBuffer bb) {
    return write(new SimpleMergedByteBuffers(false, bb));
  }
  
  @Override
  public ListenableFuture<?> write(final MergedByteBuffers mbb) {
    if(isClosed()) {
      return FutureUtils.immediateFailureFuture(new IOException("Connection is Closed"));
    }
    synchronized(writerLock) {
      final SettableListenableFuture<Long> slf = makeClientSettableListenableFuture();
      lastWriteFuture = slf;
      final boolean needNotify = !canWrite();
      if(sslProcessor != null && sslProcessor.handShakeStarted()) {
        try {
          writeBuffers.add(sslProcessor.encrypt(mbb));
        } catch (EncryptionException e) {
          this.close(e);
          return lastWriteFuture;
        }
      } else {
        writeBuffers.add(mbb);
      }
      writeFutures.add(new Pair<>(writeBuffers.getTotalConsumedBytes()+writeBuffers.remaining(), slf));
      if(needNotify && se != null && channel.isConnected()) {
        se.setClientOperations(this);
      }
      return lastWriteFuture;
    }
  }

  public ListenableFuture<?> lastWriteFuture() {
    return lastWriteFuture;
  }

  @Override
  protected ByteBuffer getWriteBuffer() {
    if(currentWriteBuffer.remaining() != 0) {
      return currentWriteBuffer;
    }
    synchronized(writerLock) {
      //This is to keep from doing a ton of little writes if we can.  We will try to 
      //do at least 8k at a time, and up to 65k if we are already having to combine buffers
      if(writeBuffers.nextBufferSize() < MIN_WRITE_BUFFER_SIZE && writeBuffers.remaining() > writeBuffers.nextBufferSize()) {
        if(writeBuffers.remaining() < MAX_COMBINED_WRITE_BUFFER_SIZE) {
          currentWriteBuffer = writeBuffers.pullBuffer(writeBuffers.remaining());
        } else {
          currentWriteBuffer = writeBuffers.pullBuffer(MAX_COMBINED_WRITE_BUFFER_SIZE);
        }
      } else {
        currentWriteBuffer = writeBuffers.popBuffer();
      }
    }
    return currentWriteBuffer;
  }

  @Override
  protected void reduceWrite(final int size) {
    synchronized(writerLock) {
      recordWriteStats(size);
      if(currentWriteBuffer.remaining() == 0) {
        while(this.writeFutures.peekFirst() != null && writeFutures.peekFirst().getLeft() <= writeBuffers.getTotalConsumedBytes()) {
          final Pair<Long, SettableListenableFuture<Long>> p = writeFutures.pollFirst();
          p.getRight().setResult(p.getLeft());
        }
      }
    }
  }

  @Override
  public InetSocketAddress getRemoteSocketAddress() {
    return remoteAddress;
  }

  @Override
  public InetSocketAddress getLocalSocketAddress() {
    return (InetSocketAddress) channel.socket().getLocalSocketAddress();
  }

  @Override
  public String toString() {
    return "TCPClient:FROM:"+getLocalSocketAddress()+":TO:"+getRemoteSocketAddress();
  }

  @Override
  public ClientOptions clientOptions() {
    return tso;
  }

  public void setSSLEngine(final SSLEngine ssle) {
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
      return sslProcessor.doHandShake();
    }
    throw new IllegalStateException("Must Set the SSLEngine before starting Encryption!");
  }

  private void doClientWrite(boolean doLocal) {
    if(isClosed()) {
      return;
    }
    int wrote = 0;
    try {
      wrote = channel.write(getWriteBuffer());
      if(wrote > 0) {
        reduceWrite(wrote);
        se.recordWriteStats(wrote);
      }
      if(!doLocal) {
        se.setClientOperations(TCPClient.this);
      }
    } catch(Exception e) {
      ExceptionUtils.handleException(e);
      close();
    }
  }

  private void doClientRead(boolean doLocal) {
    if(isClosed()) {
      return;
    }
    ByteBuffer readByteBuffer = provideReadByteBuffer();
    final int origPos = readByteBuffer.position();
    int size = 0;
    try {
      size = channel.read(readByteBuffer);
      if(size > 0) {
        readByteBuffer.position(origPos);
        final ByteBuffer resultBuffer = readByteBuffer.slice();
        readByteBuffer.position(origPos+size);
        resultBuffer.limit(size);
        addReadBuffer(resultBuffer);
        if(!doLocal) {
          se.setClientOperations(TCPClient.this);
        }
      } else if(size < 0) {
        close();
        return;
      } 

    } catch (IOException e) {
      ExceptionUtils.handleException(e);
      close();
    } 
  }

  /**
   * 
   * @author lwahlmeier
   *
   */
  private class TCPSocketOptions extends BaseClientOptions {

    @Override
    public boolean setTcpNoDelay(boolean enabled) {
      try {
        channel.socket().setTcpNoDelay(enabled);
        return true;
      } catch (SocketException e) {
        return false;
      }
    }

    @Override
    public boolean getTcpNoDelay() {
      try {
        return channel.socket().getTcpNoDelay();
      } catch (SocketException e) {
        return false;
      }
    }

    @Override
    public boolean setSocketSendBuffer(int size) {
      try {
        ArgumentVerifier.assertGreaterThanZero(size, "size");
        int prev = channel.socket().getSendBufferSize();
        channel.socket().setSendBufferSize(size);
        if(channel.socket().getSendBufferSize() != size) {
          channel.socket().setSendBufferSize(prev);
          return false;
        }
        return true;
      } catch (Exception e) {
        return false;
      }
    }

    @Override
    public int getSocketSendBuffer() {
      try {
        return channel.socket().getSendBufferSize();
      } catch (SocketException e) {
        return -1;
      }
    }

    @Override
    public boolean setSocketRecvBuffer(int size) {
      try {
        ArgumentVerifier.assertGreaterThanZero(size, "size");
        int prev = channel.socket().getReceiveBufferSize();
        channel.socket().setReceiveBufferSize(size);
        if(channel.socket().getReceiveBufferSize() != size) {
          channel.socket().setReceiveBufferSize(prev);
          return false;
        }
        return true;
      } catch (Exception e) {
        return false;
      }
    }

    @Override
    public int getSocketRecvBuffer() {
      try {
        return channel.socket().getReceiveBufferSize();
      } catch (SocketException e) {
        return -1;
      }
    }
  }
}
