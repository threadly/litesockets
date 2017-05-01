package org.threadly.litesockets;

import java.io.Closeable;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.event.ListenerHelper;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;


/**
 * <p>This is the base Client object for client communication.  Anything that reads or writes data
 * will use this object.  The clients work by having buffered reads and writes for the socket. They tell the
 * SocketExecuter when a read can be added or a write is ready for the socket.</p>
 * 
 * <p>All reads are issued on a callback on a Reader in a single threaded manor.
 * All writes will be sent in the order they are received.  In general its better to write full protocol parsable
 * packets with each write where possible.  If not possible your own locking/ordering will have to be ensured.
 * Close events will happen on there own Closer callback but it uses the same ThreadKey as the Reader thread.  
 * All Reads should be received before a close event happens.</p>
 * 
 * The client object can not function with out being in a SocketExecuter.  Writes can be added before its put
 * in the executer, but it will not write to the socket until added to the executer.
 * 
 * @author lwahlmeier
 *
 */
public abstract class Client implements Closeable {

  protected final MergedByteBuffers readBuffers = new MergedByteBuffers(false);
  protected final SocketExecuterCommonBase se;
  protected final SubmitterExecutor clientExecutor;
  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  protected final Object readerLock = new Object();
  protected final Object writerLock = new Object();
  protected final ClientByteStats stats = new ClientByteStats();
  protected final AtomicBoolean closed = new AtomicBoolean(false);
  protected final ListenerHelper<CloseListener> closerListener = new ListenerHelper<CloseListener>(CloseListener.class);
  protected volatile Runnable readerCaller = null;
  protected volatile boolean useNativeBuffers = false;
  protected volatile boolean keepReadBuffer = true;
  protected volatile int maxBufferSize = IOUtils.DEFAULT_CLIENT_MAX_BUFFER_SIZE;
  protected volatile int newReadBufferSize = IOUtils.DEFAULT_CLIENT_READ_BUFFER_SIZE;
  private ByteBuffer readByteBuffer = IOUtils.EMPTY_BYTEBUFFER;

  public Client(final SocketExecuterCommonBase se) {
    this.se = se;
    this.clientExecutor = se.getExecutorFor(this);
  }
  
  protected Client(final SocketExecuterCommonBase se, final SubmitterExecutor clientExecutor) {
    this.se = se;
    this.clientExecutor = clientExecutor;
  }

  /**
   * <p>Used by SocketExecuter to set if there was a success or error when connecting, completing the 
   * {@link ListenableFuture}.</p>
   * 
   * @param t if there was an error connecting this is provided otherwise a successful connect will pass {@code null}.
   */
  protected abstract void setConnectionStatus(Throwable t);

  /**
   * <p>This provides the next available Write buffer.  This is typically only called by the {@link SocketExecuter}.
   * This is not threadsafe as the same {@link ByteBuffer} will be provided to any thread that calls this, until 
   * something consumes all byte available in it at which point another {@link ByteBuffer} will be provided.</p>
   * 
   * <p>Assuming something actually removes data from the returned {@link ByteBuffer} an additional call to 
   * {@link #reduceWrite(int)} must be made</p>
   * 
   * 
   * @return a {@link ByteBuffer} that can be used to Read new data off the socket for this client.
   */
  protected abstract ByteBuffer getWriteBuffer();

  /**
   * <p>This is called after a write is written to the clients socket.  This tells the client how much of that 
   * {@link ByteBuffer} was written and then reduces the writeBuffersSize accordingly.</p>
   * 
   * @param size the size in bytes of data written on the socket.
   */
  protected abstract void reduceWrite(int size);

  /**
   * <p>Returns the {@link SocketChannel} for this client.  If the client does not have a {@link SocketChannel} 
   * it will return null (ie {@link UDPClient}).</p>
   * 
   * @return the {@link SocketChannel}  for this client.
   */
  protected abstract SocketChannel getChannel();
  
  /**
   * This is called when the SocketExecuter detects the socket can read.  This must be done on the clients ReadThread, 
   * and not the thread calling this. 
   */
  protected abstract void doSocketRead(boolean doLocal);
  
  /**
   * This is called when the SocketExecuter detects the socket can write.  This must be done on the clients ReadThread, 
   * and not the thread calling this. 
   */
  protected abstract void doSocketWrite(boolean doLocal);

  /**
   * 
   * @return the remote {@link SocketAddress} this client is connected to.
   */
  public abstract SocketAddress getRemoteSocketAddress();


  /**
   * 
   * @return the local {@link SocketAddress} this client is using.
   */
  public abstract SocketAddress getLocalSocketAddress();


  /**
   * Returns true if this client has data pending in its write buffers.  False if there is no data pending write.
   *
   * @return true if data is pending write, false if there is no data to write.
   */
  public abstract boolean canWrite();

  /**
   * This allows you to set/get client options for this client connection.  Not all options can
   * be set for every client.
   * 
   * @return a {@link ClientOptions} object to set/get options for this client.
   */
  public abstract ClientOptions clientOptions();

  /**
   * 
   * <p>Called to connect this client to a host.  This is done non-blocking.</p>
   * 
   * <p>If there is an error connecting {@link #close()} will also be called on the client.</p>
   * 
   * @return A {@link ListenableFuture} that will complete when the socket is connected, or fail if we cant connect.
   */
  public abstract ListenableFuture<Boolean> connect();

  /**
   * Sets the connection timeout value for this client.  This must be called before {@link #connect()} has called on this client.  
   * 
   * @param timeout the time in milliseconds to wait for the client to connect.
   */
  public abstract void setConnectionTimeout(int timeout);
  
  /**
   * <p>This tells us if the client has timed out before it has been connected to the socket.  
   * If the client has connected fully this will return false from that point on (even on a closed connection).</p>
   * 
   * @return false if the client has been connected, true if it has not connected and the timeout limit has been reached.
   */
  public abstract boolean hasConnectionTimedOut();

  /**
   * <p>Used to get this clients connection timeout information.</p>
   * 
   * @return the max amount of time to wait for a connection to connect on this socket.
   */
  public abstract int getTimeout();


  /**
   * <p>This is used to get the current size of the unWriten writeBuffer.</p>
   * 
   * @return the current size of the writeBuffer.
   */
  public abstract int getWriteBufferSize();

  /**
   * <p>This is used by the {@link SocketExecuter} to help understand how to manage this client.
   * Currently only UDP and TCP exist.</p>
   * 
   * @return The IP protocol type of this client.
   */
  public abstract WireProtocol getProtocol();

  /**
   * <p>This is called to write data to the clients socket.  Its important to note that there is no back
   * pressure when adding writes so care should be taken to now allow the clients {@link #getWriteBufferSize()} to get
   * to big.</p>
   * 
   * @param bb The {@link ByteBuffer} to write onto the clients socket. 
   * @return A {@link ListenableFuture} that will be completed once the data has been fully written to the socket.
   */
  public abstract ListenableFuture<?> write(ByteBuffer bb);
  
  public abstract ListenableFuture<?> lastWriteFuture();

  /**
   * <p>Closes this client.  Reads can still occur after this it called.  {@link CloseListener#onClose(Client)} will still be
   * called (if set) once all reads are done.</p>
   */
  public abstract void close();


  /*Implemented functions*/
  
  protected void addReadStats(final int size) {
    stats.addRead(size);
  }

  protected void addWriteStats(final int size) {
    stats.addWrite(size);
  }

  /**
   * <p>When this clients socket has a read pending and {@link #canRead()} is true, this is where the ByteBuffer for the read comes from.
   * In general this should only be used by the ReadThread in the {@link SocketExecuter} and it should be noted 
   * that it is not threadsafe.</p>
   * 
   * @return A {@link ByteBuffer} to use during this clients read operations.
   */
  protected ByteBuffer provideReadByteBuffer() {
    if(keepReadBuffer) {
      if(readByteBuffer.remaining() < IOUtils.DEFAULT_MIN_CLIENT_READ_BUFFER_SIZE) {
        if(useNativeBuffers) {
          readByteBuffer = ByteBuffer.allocateDirect(newReadBufferSize);
        } else {
          readByteBuffer = ByteBuffer.allocate(newReadBufferSize);
        }
      }
      return readByteBuffer;
    } else {
      if(useNativeBuffers) {
        return ByteBuffer.allocateDirect(newReadBufferSize);
      } else {
        return ByteBuffer.allocate(newReadBufferSize);
      }
    }

  }

  protected void callClosers() {
    this.closerListener.call().onClose(this);
  }
  protected void callReader() {
    Runnable readerCaller = this.readerCaller;
    if (readerCaller != null) {
      getClientsThreadExecutor().execute(readerCaller);
    }
  }

  /**
   * 
   * <p>Adds a {@link ByteBuffer} to the Clients readBuffer.  This is normally only used by the {@link SocketExecuter},
   * though it could be used to artificially inject data through the client.  Calling this will schedule a
   * calling the currently set Reader on the client.</p>
   * 
   * 
   * @param bb the {@link ByteBuffer} to add to the clients readBuffer.
   */
  protected void addReadBuffer(final ByteBuffer bb) {
    addReadStats(bb.remaining());
    se.addReadAmount(bb.remaining());
    int start;
    int end;
    start = readBuffers.remaining();
    readBuffers.add(bb);
    end = readBuffers.remaining();
    if(end > 0 && start == 0){
      callReader();
    }
  }

  /**
   * Returns true if this client can have reads added to it or false if its read buffers are full.
   * 
   * @return true if more reads can be added, false if not.
   */
  public boolean canRead() {
    return readBuffers.remaining() < maxBufferSize;
  }

  /**
   * <p>This is used to get the current size of the readBuffers pending reads.</p>
   * 
   * @return the current size of the ReadBuffer.
   */
  public int getReadBufferSize() {
    return readBuffers.remaining();
  }

  /**
   * <p> This returns this clients {@link SubmitterExecutor}.</p>
   * 
   * <p> Its worth noting that operations done on this {@link SubmitterExecutor} can/will block Read callbacks on the 
   * client, but it does provide you the ability to execute things on the clients read thread.</p>
   * 
   * @return The {@link SubmitterExecutor} for the client.
   */
  public SubmitterExecutor getClientsThreadExecutor() {
    return clientExecutor;
  }

  /**
   * <p>This is used to get the clients {@link SocketExecuter}.</p>
   * 
   * @return the {@link SocketExecuter} set for this client. if none, null is returned.
   */
  public SocketExecuter getClientsSocketExecuter() {
    return se;
  }

  /**
   * <p>This adds a {@link CloseListener} for this client.  Once set the client will call .onClose 
   * on it once it a socket close is detected.</p> 
   * 
   * @param closer sets this clients {@link CloseListener} callback.
   */
  public void addCloseListener(final CloseListener closer) {
    if(closed.get()) {
      getClientsThreadExecutor().execute(() -> closer.onClose(Client.this));      
    } else {
      closerListener.addListener(closer, this.getClientsThreadExecutor());
    }
  }

  /**
   * <p>This sets the Reader for the client.  Once set data received on the socket will be callback 
   * on this Reader to be processed.  If no reader is set before connecting the client read data will just
   * queue up till we hit the the max buffer size</p>
   * 
   * @param reader the {@link Reader} callback to set for this client.
   */
  public void setReader(final Reader reader) {
    if(! closed.get()) {
      if (reader == null) {
        readerCaller = null;
      } else {
        synchronized(readerLock) {
          readerCaller = () -> reader.onRead(this);
          if (this.getReadBufferSize() > 0) {
            callReader();
          }
        }
      }
    }
  }

  /**
   * <p>Whenever a the {@link Reader} Interfaces {@link Reader#onRead(Client)} is called the
   * {@link #getRead()} should be called from the client.</p>
   * 
   * @return a {@link MergedByteBuffers} of the current read data for this client.
   */
  public MergedByteBuffers getRead() {
    synchronized(readerLock) {
      MergedByteBuffers mbb = readBuffers.duplicateAndClean();
      if(mbb.remaining() >= maxBufferSize) {
        se.setClientOperations(this);
      }
      return mbb;
    }
  }

  /**
   * <p>Returns if this client is closed or not.  Once a client is marked closed there is no way to reOpen it.
   * You must just make a new client.  Just because this returns false does not mean the client is connected.
   * Before a client connects, but has not yet closed this will be false.</p>
   * 
   * @return true if the client is closed, false if the client has not yet been closed.
   */
  public boolean isClosed() {
    return closed.get();
  }

  protected boolean setClose() {
    return closed.compareAndSet(false, true);
  }

  /**
   * Returns the {@link SimpleByteStats} for this client.
   * 
   * @return the byte stats for this client.
   */
  public SimpleByteStats getStats() {
    return stats;
  }

  /**
   * Implementation of the SimpleByteStats.
   */
  private static class ClientByteStats extends SimpleByteStats {
    public ClientByteStats() {
      super();
    }

    @Override
    protected void addWrite(final int size) {
      ArgumentVerifier.assertNotNegative(size, "size");
      super.addWrite(size);
    }

    @Override
    protected void addRead(final int size) {
      ArgumentVerifier.assertNotNegative(size, "size");
      super.addRead(size);
    }
  }

  /**
   * Used to notify when a Client there is data to Read for a Client.
   * 
   * <p>Any client with this set will call .onRead(client) when a read is read from the socket.
   * These will happen in a single threaded manor for that client.  The thread used is the clients
   * thread and should not be blocked for long.  If it is the client will back up and stop reading until
   * it is unblocked.</p>
   * 
   * <p>The implementor of Reader should call {@link Client#getRead()}.</p>
   * 
   * <p>If the same Reader is used by multiple clients each client will call the Reader on its own thread so be careful 
   * what objects your modifying if you do that.</p>
   * 
   * 
   *
   */
  public interface Reader {
    /**
     * <p>When this callback is called it will pass you the Client object
     * that did the read.  .getRead() should be called on once and only once
     * before returning.  If it is failed to be called or called more then once
     * you could end up with uncalled data on the wire or getting a null.</p>
     * 
     * @param client This is the client the read is being called for.
     */
    public void onRead(Client client);
  }


  /**
   * Used to notify when a Client is closed.
   * 
   * <p>It is also single threaded on the same thread key as the reads.
   * No action must be taken when this is called but it is usually advised to do some kind of clean up or something
   * as this client object will no longer be used for anything.</p>
   * 
   */
  public interface CloseListener {
    /**
     * This notifies the callback about the client being closed.
     * 
     * @param client This is the client the close is being called for.
     */
    public void onClose(Client client);
  }

  /**
   * ClientOptions that can be changed depending on what kind of client you want.
   * 
   * In general these should not be set unless you have a very specific use case.
   * 
   * @author lwahlmeier
   *
   */
  public interface ClientOptions {

    /**
     * This is only available for connection backed by a TCP socket.
     * It will turn on TcpNoDelay on the the connection at the System level.
     * This means that data queued to go out the socket will not delay before being sent.
     * 
     * @param enabled true means NoDelay is on, false means NoDelay is off.
     * @return true if this was able to be set.
     */
    public boolean setTcpNoDelay(boolean enabled);

    /**
     * Returns the current state of TcpNoDelay.
     * 
     * @return true means NoDelay is on, false means NoDelay is off.
     */
    public boolean getTcpNoDelay();

    /**
     * Sets this client to use Native or Direct ByteBuffers.
     * This can save allocations to the Heap, but is generally only useful
     * for things like pass through proxies.
     * 
     * @param enabled true means use Native buffers false means use Heap buffers.
     * @return true if this was able to be set.
     */
    public boolean setNativeBuffers(boolean enabled);

    /**
     * Returns the current state of native buffers.
     * 
     * @return true means native buffers are generated false means they are not. 
     */
    public boolean getNativeBuffers();

    /**
     * Sets reduced Read buffer allocations.  This is accomplished by over allocating 
     * the read buffer and returning subsets of it.  This can make reads much faster but
     * does also use more memory.
     * 
     * @param enabled true for enabled false for disabled.
     * @return true if this was able to be set.
     */
    public boolean setReducedReadAllocations(boolean enabled);

    /**
     * Returns the current state of ReducedReadAllocations.
     * 
     * @return true for enabled false for disabled.
     */
    public boolean getReducedReadAllocations();

    /**
     * Sets the max size of read buffer the client is allowed to have.
     * Once this is reached the client will stop doing read operations until 
     * the Read buffers gets under this size.  This can really effect performance 
     * especially if its set to small.
     * 
     * @param size in bytes. 
     * @return true if this was able to be set.
     */
    public boolean setMaxClientReadBuffer(int size);

    /**
     * Returns the currently set max Read buffer size in Bytes.
     * 
     * @return size of max read buffer size.
     */
    public int getMaxClientReadBuffer();

    /**
     * Sets the size of the ByteBuffer used for Reads.  The larger this
     * buffer is the more data we can read from the socket at once.  If
     * {@link #getReducedReadAllocations()} is true we will reuse the unused
     * space in this buffer until it gets below the minimum read threshold.
     * 
     * @param size in bytes.
     * @return true if this was able to be set.
     */
    public boolean setReadAllocationSize(int size);

    /**
     * Returns the current Read buffer allocation size in bytes.
     * 
     * @return bytes allocated for reads.
     */
    public int getReadAllocationSize();

    /**
     * This sets the System level socket send buffer size.  Every OS
     * has its own min and max values for this, if you go over or under that
     * it will not be set.
     * 
     * @param size buffer size in bytes.
     * @return true if this was able to be set.
     */
    public boolean setSocketSendBuffer(int size);

    /**
     * Returns the currently set send buffer size in bytes.
     * 
     * @return send buffer size in bytes.
     */
    public int getSocketSendBuffer();

    /**
     * This sets the System level socket receive buffer size.  Every OS
     * has its own min and max values for this, if you go over or under that
     * it will not be set.
     * 
     * @param size buffer size in bytes.
     * @return true if this was able to be set.
     */
    public boolean setSocketRecvBuffer(int size);

    /**
     * Returns the currently set receive buffer size in bytes.
     * 
     * @return send buffer size in bytes.
     */
    public int getSocketRecvBuffer();

    /**
     * Sets the UDP frame size.  This only possible on UDP backed clients.
     * 
     * @param size max frame size in bytes
     * @return true if this was able to be set.
     */
    public boolean setUdpFrameSize(int size);

    /**
     * Returns the currently set UDP frame size in bytes.
     * 
     * @return frame size in bytes.
     */
    public int getUdpFrameSize();
  }

  /**
   * 
   * @author lwahlmeier
   *
   */
  protected class BaseClientOptions implements ClientOptions {

    @Override
    public boolean setNativeBuffers(boolean enabled) {
      useNativeBuffers = enabled;
      return true;
    }

    @Override
    public boolean getNativeBuffers() {
      return useNativeBuffers;
    }

    @Override
    public boolean setReducedReadAllocations(boolean enabled) {
      keepReadBuffer = enabled;
      if(!keepReadBuffer) {
        readByteBuffer = IOUtils.EMPTY_BYTEBUFFER;
      }
      return true;
    }

    @Override
    public boolean getReducedReadAllocations() {
      return keepReadBuffer;
    }

    @Override
    public boolean setReadAllocationSize(int size) {
      newReadBufferSize = size;
      return true;
    }

    @Override
    public int getReadAllocationSize() {
      return newReadBufferSize;
    }

    @Override
    public boolean setMaxClientReadBuffer(int size) {
      maxBufferSize = size;
      return true;
    }

    @Override
    public int getMaxClientReadBuffer() {
      return maxBufferSize;
    }

    @Override
    public boolean setTcpNoDelay(boolean enabled) {
      return false;
    }

    @Override
    public boolean getTcpNoDelay() {
      return false;
    }

    @Override
    public boolean setSocketSendBuffer(int size) {
      return false;
    }

    @Override
    public int getSocketSendBuffer() {
      return -1;
    }

    @Override
    public boolean setSocketRecvBuffer(int size) {
      return false;
    }

    @Override
    public int getSocketRecvBuffer() {
      return -1;
    }

    @Override
    public boolean setUdpFrameSize(int size) {
      return false;
    }

    @Override
    public int getUdpFrameSize() {
      return -1;
    }

  }
}

