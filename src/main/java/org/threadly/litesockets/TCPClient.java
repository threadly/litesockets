package org.threadly.litesockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SSLProcessor;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;
import org.threadly.util.Pair;


/**
 * A Simple TCP client.
 *   
 */
@SuppressWarnings("deprecation")
public class TCPClient extends Client {
  protected static final int DEFAULT_SOCKET_TIMEOUT = 10000;
  protected static final int MIN_WRITE_BUFFER_SIZE = 8192;
  protected static final int MAX_COMBINED_WRITE_BUFFER_SIZE = 65536;

  private final MergedByteBuffers writeBuffers = new MergedByteBuffers();
  private final Deque<Pair<Long, SettableListenableFuture<Long>>> writeFutures = new ArrayDeque<Pair<Long, SettableListenableFuture<Long>>>();
  private final TCPSocketOptions tso = new TCPSocketOptions();
  protected final AtomicBoolean startedConnection = new AtomicBoolean(false);
  protected final SettableListenableFuture<Boolean> connectionFuture = new SettableListenableFuture<Boolean>(false);
  protected final SocketChannel channel;
  protected final InetSocketAddress remoteAddress;
  
  private volatile ByteBuffer currentWriteBuffer = ByteBuffer.allocate(0);
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
  protected TCPClient(final SocketExecuter sei, final String host, final int port) throws IOException {
    super(sei);
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
  protected TCPClient(final SocketExecuter sei, final SocketChannel channel) throws IOException {
    super(sei);
    if(! channel.isOpen()) {
      throw new ClosedChannelException();
    }
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
    return Clock.accurateForwardProgressingMillis() > connectExpiresAt; 
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
  public void close() {
    if(setClose()) {
      se.setClientOperations(this);
      final ClosedChannelException cce = new ClosedChannelException();
      this.getClientsThreadExecutor().execute(new Runnable() {
        @Override
        public void run() {
          synchronized(writerLock) {
            for(final Pair<Long, SettableListenableFuture<Long>> p: writeFutures) {
              p.getRight().setFailure(cce);
            }
            writeFutures.clear();
            writeBuffers.discard(writeBuffers.remaining());
          }
        }});
      this.callClosers();
    }
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.TCP;
  }

  @Override
  public boolean canWrite() {
    return writeBuffers.remaining() > 0 ;
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
  public void setMaxBufferSize(final int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    maxBufferSize = size;
    if(channel.isConnected()) {
      this.se.setClientOperations(this);
    }
  }
  
  @Override
  public MergedByteBuffers getRead() {
    MergedByteBuffers mbb = super.getRead();
    if(sslProcessor != null && sslProcessor.handShakeStarted() && mbb.remaining() > 0) {
      mbb = sslProcessor.decrypt(mbb);
    }
    return mbb;
  }

  @Override
  public ListenableFuture<?> write(final ByteBuffer bb) {
    if(isClosed()) {
      throw new IllegalStateException("Cannot write to closed client!");
    }
    synchronized(writerLock) {
      final boolean needNotify = ! canWrite();
      final SettableListenableFuture<Long> slf = new SettableListenableFuture<Long>(false);
      if(sslProcessor != null && sslProcessor.handShakeStarted()) {
        writeBuffers.add(sslProcessor.encrypt(bb));
      } else {
        writeBuffers.add(bb);
      }
      writeFutures.add(new Pair<Long, SettableListenableFuture<Long>>(writeBuffers.getTotalConsumedBytes()+writeBuffers.remaining(), slf));
      if(needNotify && se != null && channel.isConnected()) {
        se.setClientOperations(this);
      }
      
      return slf;
    }
  }

  @Override
  protected ByteBuffer getWriteBuffer() {
    if(currentWriteBuffer.remaining() != 0) {
      return currentWriteBuffer;
    }
    synchronized(writerLock) {
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
  protected void reduceWrite(final int size) {
    synchronized(writerLock) {
      addWriteStats(size);
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
  public boolean setSocketOption(final SocketOption so, final int value) {
    try{
      switch(so) {
      case TCP_NODELAY: {
          return tso.setTcpNoDelay(value > 0);
      }
      case SEND_BUFFER_SIZE: {
        return tso.setSocketSendBuffer(value);
      }
      case RECV_BUFFER_SIZE: {
        return tso.setSocketRecvBuffer(value);
      }
      case USE_NATIVE_BUFFERS: {
        return tso.setNativeBuffers(value > 0);
      }
      default:
        return false;
      }
    } catch(Exception e) {
    }
    return false;
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
