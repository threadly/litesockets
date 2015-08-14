package org.threadly.litesockets;

import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.SocketExecuterInterface.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;


/**
 * <p>This is the main Client interface used in litesockets.  Anything that reads or writes data
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
public abstract class Client {
  
  /**
   * Returns true if this client can have reads added to it or false if its read buffers are full.
   * 
   * This is mainly to inform the SocketExecuter if the client should be allowed to select onReads.
   * 
   * @return true if more reads can be added, false if not.
   */
  protected abstract boolean canRead();

  /**
   * Returns true if this client has data pending in its write buffers.  False if there is no data pending write.
   *
   * <p>Unlike canRead() and has no relation to if the write buffer is full or not.  Basically if there is anything in the 
   * write buffer this should return true so the SocketExecuter knows to add this client to onWrite selector.</p>
   * 
   * @return true if data is pending write, false if there is no data to write.
   */
  protected abstract boolean canWrite();

  /**
   * <p>This tells us if the client has timed out before it has been connected to the socket.  This is used to remove the client
   * from the selector when we have reached our timeout.  With nio there is no way to have it automatically 
   * wake up the selector and time it out.  If the client has connected fully this will return false from that point on 
   * (even on a closed connection).</p>
   * 
   * @return false if the client has been connected, true if it has not connected and the timeout limit has been reached.
   */
  public abstract boolean hasConnectionTimedOut();
  
  /**
   * 
   * <p>Called to connect this client to a host.  This is done non-blocking, and can be called before adding the client 
   * to the {@link SocketExecuterInterface}, but the client must be on the {@link SocketExecuterInterface} 
   * in order to finish connecting.  If not called {@link SocketExecuterInterface#addClient(Client)}
   * will automatically call this.</p>
   * 
   * <p>If there is an error connecting {@link #close()} will also be called on the client.</p>
   * 
   * @return A {@link ListenableFuture} that will complete when the socket is connected, or fail if we cant connect.
   */
  public abstract ListenableFuture<Boolean> connect();
  
  /**
   * <p>This is generally used by SocketExecuter to set if there was a success or error when connecting, completing the 
   * {@link ListenableFuture}.</p>
   * 
   * @param t if there was an error connecting this is provided otherwise a successful connect will pass {@code null}.
   */
  protected abstract void setConnectionStatus(Throwable t);
  
  /**
   * <p>Used to get this clients connection timeout information.</p>
   * 
   * @return the max amount of time to wait for a connection to connect on this socket.
   */
  public abstract int getTimeout();
  
  /**
   * <p>When this clients socket has a read pending and {@link #canRead()} is true, this is where the ByteBuffer for the read comes from.
   * In general this should only be used by the ReadThread in the {@link SocketExecuterInterface} and it should be noted 
   * that it is not threadsafe.</p>
   * 
   * @return A ByteBuffer for the ReadThread to use during its read operations.
   */
  protected abstract ByteBuffer provideReadByteBuffer();
  
  /**
   * <p>This is used to get the current size of the readBuffers pending reads.</p>
   * 
   * @return the current size of the ReadBuffer.
   */
  public abstract int getReadBufferSize();
  
  /**
   * <p>This is used to get the current size of the unWriten writeBuffer.</p>
   * 
   * @return the current size of the writeBuffer.
   */
  public abstract int getWriteBufferSize();
  
  /**
   * This is used to get the currently set max buffer size.
   * 
   * @return the current MaxBuffer size allowed.  The read and write buffer use this independently.
   */
  public abstract int getMaxBufferSize();
  
  /**
   * <p> This returns this clients {@link Executor}.  The client must have been added to the {@link SocketExecuterInterface} or 
   * this will return null.</p>
   * 
   * <p> Its worth noting that operations done on this {@link Executor} can/will block Read callbacks on the 
   * client, but it does provide you the ability to execute things on the clients read thread</p>
   * 
   * @return The {@link Executor} for the client.
   */
  public abstract Executor getClientsThreadExecutor();
  
  /**
   * <p>This is set when the client is added to a {@link SocketExecuterInterface}.  Care should be given if you manually set this as 
   * it will greatly impact the behavior of the client {@link Reader} callback.<p>
   * 
   * @param cte the {@link Executor} to used for this clients callbacks.
   */
  protected abstract void setClientsThreadExecutor(Executor cte);
  
  /**
   * <p>This is used to get the clients currently assigned {@link SocketExecuterInterface}.</p>
   * 
   * @return the {@link SocketExecuterInterface} set for this client. if none, null is returned.
   */
  public abstract SocketExecuterInterface getClientsSocketExecuter();
  
  /**
   * <p>This is automatically done when called the client is added with {@link SocketExecuterInterface#addClient(Client)}.  
   * In general there is no need to manually set this, and care should be given if done.</p>
   * 
   * @param cse the {@link SocketExecuterInterface} for this client.
   */
  protected abstract void setClientsSocketExecuter(SocketExecuterInterface cse);
  
  /**
   * <p>This is used to get the currently set {@link Closer} for this client.</p>
   * 
   * @return the current {@link Closer} interface for this client.
   */
  public abstract Closer getCloser();

  /**
   * <p>This sets the {@link Closer} interface for this client.  Once set the client will call .onClose 
   * on it once it a socket close is detected.</p> 
   * 
   * @param closer sets this clients {@link Closer} callback.
   */
  public abstract void setCloser(Closer closer);
  
  /**
   * <p>Returns the currently set Reader callback. </p>
   * 
   * @return the current {@link Reader} callback for this client.
   */
  public abstract Reader getReader();

  /**
   * <p>This sets the Reader for the client.This should be set before adding the Client to the {@link SocketExecuterInterface}, 
   * if its not there is a chance to miss data coming in on the socket.  Once set data received on the socket will be callback 
   * on this Reader to be processed.</p>
   * 
   * @param reader the {@link Reader} callback to set for this client.
   */
  public abstract void setReader(Reader reader);

  /**
   * <p>This allows you to set/change the max buffer size for this client object.
   * This is the in java memory buffer not the additional socket buffer the OS might setup.</p>
   * 
   * <p>In general this should be set to the max size you can deal with.  The lower this is the more often we
   * will end up adding/removing the client from the selectors.  Both the read and write buffers are separate so 
   * a client can use up to 2x the buffer size set here.  You need room in your heap for buffers for all clients.
   * These buffers are not kept at full size, so clients will rarely use that much memory if the protocol parsing 
   * and network are keeping up with the data going in/out.</p>
   * 
   * @param size max buffer size in bytes.
   */
  public abstract void setMaxBufferSize(int size);
  
  /**
   * <p>Whenever a the {@link Reader} Interfaces {@link Reader#onRead(Client)} is called the
   * {@link #getRead()} should be called from the client.</p>
   * 
   * @return a {@link MergedByteBuffers} of the current read data for this client.
   */
  public abstract MergedByteBuffers getRead();
  
  /**
   * 
   * <p>Adds a {@link ByteBuffer} to the Clients readBuffer.  This is normally only used by the {@link SocketExecuterInterface},
   * though it could be used to artificially inject data through the client.  Calling this will schedule a
   * calling the currently set Reader on the client.</p>
   * 
   * 
   * @param bb the {@link ByteBuffer} to add to the clients readBuffer.
   */
  protected abstract void addReadBuffer(ByteBuffer bb);
  
  /**
   * <p> This tries to write the ByteBuffer passed to it to the Client.  If the Clients writeBuffer 
   * is greater than the maxbufferSize the {@link ByteBuffer} will not be added and false will be returned.
   * If the current writeBuffer is less than the maxBufferSize then the ByteBuffer will be added and this
   * will return true.</p>
   * 
   * <p>As this write is thread safe it should be noted that calling this with multiThreads can not garentee order.
   * As such it is recommended to write full parsable protocol packets at a time, or implement your own locking around the
   * write if you are streaming data raw data and using more then 1 thread to do such.
   * </p>
   * 
   * @param bb the {@link ByteBuffer} to write to the client.
   * @return true if the client has taken the ByteBuffer false if it did not.
   */
  @Deprecated
  public abstract boolean writeTry(ByteBuffer bb);
  
  /**
   * <p>This write will block until the write can be done.  This block will only happen if the clients
   * current writeBuffer size is more than the set maxBuffer.  This block will persist either until
   * the clients writeBuffer is less than the maxWriteBuffer or the client is closed.</p>
   * 
   * <p>Care should be taken when using this.  The pending writes for the client will happen on another unblockable 
   * thread but if a lot of clients use there read thread to write and block its possible to consume all threads
   * in the pool.  This is only a problem if the thread pool is reading/writing from both sides of a client connection
   * using the same thread pool.</p>
   * 
   * <p>As this write is thread safe it should be noted that calling this with multiThreads can not garentee order.
   * As such it is recommended to write full parsable protocol packets at a time, or implement your own locking around the
   * write if you are streaming data raw data and using more then 1 thread to do such.
   * </p>
   * 
   * @param bb the {@link ByteBuffer} to write.
   * @throws InterruptedException This happens only if the thread that is blocked is interrupted while waiting. 
   */
  @Deprecated
  public abstract void writeBlocking(ByteBuffer bb) throws InterruptedException;
  
  /**
   * <p>This write forces the client to go over its maxBufferSize.  This can be dangerous if used incorrectly.
   * Its assumed if this is used you are keeping track of the clients writeBuffer on your own.</p>
   * 
   * <p>As this write is thread safe it should be noted that calling this with multiThreads can not garentee order.
   * As such it is recommended to write full parsable protocol packets at a time, or implement your own locking around the
   * write if you are streaming data raw data and using more then 1 thread to do such.
   * </p>
   * 
   * @param bb the ByteBuffer to write to the client.
   */
  @Deprecated
  public abstract void writeForce(ByteBuffer bb);
  
  
  public abstract ListenableFuture<?> write(ByteBuffer bb);

  
  /**
   * <p>This provides the next available Write buffer.  This is typically only called by the {@link SocketExecuterInterface}.
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
   * it will return null (ie {@link org.threadly.litesockets.udp.UDPClient}).</p>
   * 
   * @return the {@link SocketChannel}  for this client.
   */
  protected abstract SocketChannel getChannel();
  
  /**
   * <p>This is used by the {@link SocketExecuterInterface} to help understand how to manage this client.
   * Currently only UDP and TCP exist.</p>
   * 
   * @return The IP protocol type of this client.
   */
  public abstract WireProtocol getProtocol();
  
  /**
   * <p>Gets the raw Socket object for this Client. If the client does not have a Socket
   * it will return null (ie {@link org.threadly.litesockets.udp.UDPClient}). This is basically getChannel().socket()</p>
   * 
   * @return the Socket for this client.
   */
  protected abstract Socket getSocket();
  
  /**
   * <p>Returns if this client is closed or not.  Once a client is marked closed there is no way to reOpen it.
   * You must just make a new client.  Just because this returns false does not mean the client is connected.
   * Before a client connects, but has not yet closed this will be false.</p>
   * 
   * @return true if the client is closed, false if the client has not yet been closed.
   */
  public abstract boolean isClosed();
  
  /**
   * <p>Closes this client.  Reads can still occur after this it called.  {@link Closer#onClose(Client)} will still be
   * called (if set) once all reads are done.</p>
   */
  public abstract void close();
  
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
   * Returns the {@link SimpleByteStats} for this client.
   * 
   * @return the byte stats for this client.
   */
  public abstract SimpleByteStats getStats();
  
  /**
   * This is the Reader Interface for clients.
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
   * This is the Closer Interface for clients.
   * 
   * <p>This works almost exactly like Reader.  It is also single threaded on the same thread key as the reads.
   * No action must be taken when this is called but it is usually advised to do some kind of clean up or something
   * as this client object will no longer ever be used.</p>
   * 
   */
  public interface Closer {
    /**
     * This notifies the callback about the client being closed.
     * 
     * @param client This is the client the close is being called for.
     */
    public void onClose(Client client);
  }
  
}

