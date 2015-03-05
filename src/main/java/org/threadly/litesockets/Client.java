package org.threadly.litesockets;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.litesockets.SocketExecuterBase.WireProtocol;
import org.threadly.litesockets.utils.SimpleByteStats;

/**
 * This is the main Client object used in litesockets.  Anything that reads or writes data
 * will use this object.  The clients work by having buffered reads and writes for the socket.
 * 
 * All reads will issue on a callback on a Reader in a single threaded manor.
 * All writes will be sent in the order they are received.  In general its better to write full protocol parsable
 * packets with each write where possible.  If not possible your own locking/ordering will have to be ensured.
 * Close events will happen on there own Closer callback but it uses the same ThreadKey as the Reader thread.  
 * All Reads should be received before a close event happens.
 * 
 * The client object can not functions with out being in a SocketExecuter.
 * 
 * @author lwahlmeier
 *
 */
public interface Client {
  
  /**
   * Returns the SimpleByteStats object for this client.
   * 
   * @return the byte stats for this client.
   */
  public SimpleByteStats getStats();
  
  /**
   * Returns true if this client can read or false if it can not read any more data.
   * This is in releation to the maxBufferSize vs the current readBuffer size.
   * 
   * @return true if more reads can be added, false if not.
   */
  public boolean canRead();

  /**
   * Returns true if this client can accept more ByteBuffers to write.
   * False is returned if the number of bytes pending write is greater than the max buffer size.
   * 
   * @return true if it can write more false if it cant.
   */
  public boolean canWrite();
  
  /**
   * When this clients socket has a read pending this is where the byteBuffer for the read comes from.
   * It is assumed that this will only be used by the ReadThread on its socketExecuter and it checks to 
   * make sure thats the case.  The ByteBuffer returned is not thread safe.
   * 
   * @return A ByteBuffer for the ReadThread to use during its read operations.
   */
  public ByteBuffer provideEmptyReadBuffer();
  
  /**
   * This is used to get the current size of the unRead readBuffer.
   * If this is greater then the maxBufferSize the client will be removed
   * from the SocketExecuters reading operations.
   * 
   * @return the current size of the ReadBuffer.
   */
  public int getReadBufferSize();
  
  /**
   * This is used to get the current size of the unWriten writeBuffer.
   * If this is greater then the maxBufferSize the client will stop accepting
   * new writes (except for forced writes).
   * 
   * @return the current size of the writeBuffer.
   */
  public int getWriteBufferSize();
  
  /**
   * This is used to get the currently set max buffer size.
   * 
   * @return the current MaxBuffer size allowed.  The read and write buffer use this independently.
   */
  public int getMaxBufferSize();
  
  public SubmitterExecutorInterface getClientsThreadExecutor();
  public void setClientsThreadExecutor(SubmitterExecutorInterface cte);
  
  /**
   * This is used to get the clients currently assigned SocketExecuter.
   * 
   * @return the SocketExecuter set for this client. if none, null is returned.
   */
  public SocketExecuterBase getClientsSocketExecuter();
  public void setClientsSocketExecuter(SocketExecuterBase cse);
  
  /**
   * This is used to get the current Closer for this client.
   * 
   * @return the current Closer interface for this client.
   */
  public Closer getCloser();

  /**
   * This sets the Closer interface for this client.  
   * Once set the client will call .onClose on it once it a socket close is detected. 
   * 
   * @param closer sets this clients Closer interface.
   */
  public void setCloser(Closer closer);
  
  /**
   * Returns the currently set Reader callback.
   * 
   * @return the current Reader for this client.
   */
  public Reader getReader();

  /**
   * This sets the Reader for the client. 
   * The Reader is called every time a new read is added to the client
   * Each time a Reader is called it should call .getRead() *once* on the client
   * 
   * @param reader the Reader interface to set for this client.
   */
  public void setReader(Reader reader);

  /**
   * This allows you to set/change the max buffer size for this client object.
   * This is the in java memory buffer not the additional socket buffer the OS might setup. 
   * 
   * @param size in bytes.
   */
  public void setMaxBufferSize(int size);
  
  /**
   * Whenever a Reader is called for this client a .getRead() should be called.
   * 
   * @return a ByteBuffer of a read for this client.
   */
  public ByteBuffer getRead();
  
  /**
   * This is for the SocketExecuter.  Once a read is done from the socket this is called.
   * 
   * This call should also make a call to addReadBufferSize(int size).
   * 
   * @param bb the ByteBuffer read off the Socket.
   */
  public void addReadBuffer(ByteBuffer bb);
  
  /**
   * This will try to write a ByteBuffer to the client. 
   * If the current writeBuffer size is less than the Max buffer size this will return true.
   * 
   * Otherwise you will get a false.  If a false is received the buffer *will not* write to the 
   * socket unless you call another write again. 
   * 
   * It should be noted that either full parseable packets should be written at a time 
   * or some other form of locking should be used.
   * 
   * @param bb the ByteBuffer to write to the client.
   * @return true if the client has taken the ByteBuffer false if it did not.
   */
  public boolean writeTry(ByteBuffer bb);
  
  /**
   * This write will block until the write can be done.  This block will only happen if the clients
   * current writeBuffer size is more than the set maxBuffer.  This block will persist either until
   * the clients writeBuffer is less than the maxWriteBuffer or the client is closed.
   * 
   * Care should be taken when using this.  The pending writes for the client will happen on another unblockable 
   * thread but if a lot of clients use there read thread to write and block its possible to consume all threads
   * in the pool.  This is only a problem if the thread pool is reading/writting from both sides of a client connection
   * using the same thread pool.
   * 
   * @param bb the ByteBuffer to write.
   * @throws InterruptedException This happens only if the thread that is blocked is interrupted while waiting. 
   */
  public void writeBlocking(ByteBuffer bb) throws InterruptedException;
  
  /**
   * This write basically forces the client to go over its maxBufferSize.  This technically does not have
   * to be implemented by the client and can be dangerous if done incorrectly.
   * 
   * 
   * @param bb the ByteBuffer to write to the client.
   */
  public void writeForce(ByteBuffer bb);
  
  /**
   * This is called by the SocketExecuter once the clients socket is able to be written to.
   * It is up to the clients implementer to figure out how to deal with combining or not combining ByteBuffers 
   * for write.  In general its much better to just send the byteBuffers as they where received but the write functions. 
   * 
   * @return a ByteBuffer that can be used to Read new data off the socket for this client.
   */
  public ByteBuffer getWriteBuffer();
  
  /**
   * This is called after a write is written to the clients socket.  This tells the client how much of that ByteBuffer was written.
   * This should also call removeFromWriteSize(size). to reduce the buffer and update stats.
   * 
   * @param size the size in bytes of data written on the socket.
   */
  public void reduceWrite(int size);
  
  /**
   * Returns the SocketChannel for this client.  If the client does not have a SocketChannel
   * it will return null (ie UDPClient).
   * 
   * @return the SocketChannel for this client.
   */
  public SocketChannel getChannel();
  
  /**
   * This is used by the SocketExecuter to help understand how to manage this client.
   * Currently only UDP and TCP exist.
   * 
   * @return The IP protocol type of this client.
   */
  public WireProtocol getProtocol();
  
  /**
   * Gets the raw Socket object for this Client.
   * 
   * @return the Socket for this client.
   */
  public Socket getSocket();
  
  /**
   * Returns if this client is closed or not.  Once a client is marked closed there is no way to reOpen it.
   * You must just make a new client.
   * 
   * @return true if the client is closed, false if the client is connected.
   */
  public boolean isClosed();
  
  /**
   * closes this client.  Any pending writes when this is called will just be dropped.
   */
  public void close();
  
  /**
   * This is the Reader Interface for clients.
   * Any client with this set will call .onRead(client) when a read is read from the socket.
   * These will happen in a single threaded manor for that client.  The thread used is the clients
   * thread and should not be blocked for long.  If it is the client will back up and stop reading until
   * it is unblocked.
   * 
   * The implementor of Reader should always call "client.getRead()" once and only once.  If not you risk either
   * reading data to early or causing the reader to be behind a packet.
   * 
   * If the same Reader is used by multiple clients each client will call the Reader on its own thread so be careful 
   * what objects your modifying if you do that.
   * 
   * 
   * @author lwahlmeier
   *
   */
  public interface Reader {
    /**
     * When this callback is called it will pass you the Client object
     * that did the read.  .getRead() should be called on once and only once
     * before returning.  If it is failed to be called or called more then once
     * you could end up with uncalled data on the wire or getting a null.
     * 
     * @param client This is the client the read is being called for.
     */
    public void onRead(Client client);
  }
  
  
  /**
   * This is the Closer Interface for clients.
   * This works almost exactly like Reader.  It is also single threaded on the same thread key as the reads.
   * No action must be taken when this is called but it is usually advised to do some kind of clean up or something
   * as this client object will no longer ever be used.
   * 
   * @author lwahlmeier
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

