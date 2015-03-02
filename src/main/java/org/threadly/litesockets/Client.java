package org.threadly.litesockets;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.litesockets.SocketExecuterBase.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
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
public abstract class Client {
  public static final int DEFAULT_MAX_BUFFER_SIZE = 64*1024;
  public static final int MIN_READ= 4*1024;
  
  private final AtomicInteger readBufferSize = new AtomicInteger(0);
  private final AtomicInteger writeBufferSize = new AtomicInteger(0);
  
  protected ClientByteStats stats = new ClientByteStats();
  
  protected int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
  protected int minAllowedReadBuffer = MIN_READ;
  
  private final MergedByteBuffers readBuffers = new MergedByteBuffers();
  private final MergedByteBuffers writeBuffers = new MergedByteBuffers();
  private ByteBuffer currentWriteBuffer;
  private ByteBuffer readByteBuffer = ByteBuffer.allocate(maxBufferSize*2);
  
  protected volatile Closer closer;
  protected volatile Reader reader;
  protected volatile SubmitterExecutorInterface sei;
  protected volatile SocketExecuterBase ce;
  protected AtomicBoolean closed = new AtomicBoolean(false);
  
  /**
   * Returns the SimpleByteStats object for this client.
   * 
   * @return the byte stats for this client.
   */
  public SimpleByteStats getStats() {
    return stats;
  }
  
  /**
   * Returns true if this client can read or false if it can not read any more data.
   * This is in releation to the maxBufferSize vs the current readBuffer size.
   * 
   * @return true if more reads can be added, false if not.
   */
  public boolean canRead() {
    if(readBufferSize.get() > maxBufferSize) {
      return false;
    } 
    return true;
  }

  /**
   * Returns true if this client can accept more ByteBuffers to write.
   * False is returned if the number of bytes pending write is greater than the max buffer size.
   * 
   * @return true if it can write more false if it cant.
   */
  public boolean canWrite() {
    if(writeBufferSize.get() > 0) {
      return true;
    }
    return false;
  }
  
  /**
   * When this clients socket has a read pending this is where the byteBuffer for the read comes from.
   * It is assumed that this will only be used by the ReadThread on its socketExecuter and it checks to 
   * make sure thats the case.  The ByteBuffer returned is not thread safe.
   * 
   * @return A ByteBuffer for the ReadThread to use during its read operations.
   */
  protected ByteBuffer provideEmptyReadBuffer() {
    if(! ce.verifyReadThread()) {
      ce.removeClient(this);
      throw new IllegalStateException("Only the Client Executers ReadThread can access this function!! Client removed from Executer!");
    }
    if(readByteBuffer.remaining() < minAllowedReadBuffer) {
      readByteBuffer = ByteBuffer.allocate(maxBufferSize*2);
    }
    return readByteBuffer;
  }
  
  /**
   * When a read is read from the socket the size of the read is reported to the client.
   * This also reports the read size to the statistics of this client.
   * 
   * This should be called by the client when the SocketExecuter calls addReadBuffer().
   * 
   * @param size The size if the read in bytes from the socket.
   */
  protected void addToReadSize(int size) {
    stats.addRead(size);
    readBufferSize.addAndGet(size);
  }
  
  /**
   * When a write is added to the client this is called to report the size of the write
   * to the client.  This write is added to the buffer size with this call.
   * 
   * 
   * @param size The size of the write in bytes.
   */
  protected void addToWriteSize(int size) {
    writeBufferSize.addAndGet(size);
  }
  
  /**
   * This should be called when getRead() is called.  This reduces the read buffer by said amount. 
   * 
   * @param size The size in bytes that is being removed from the buffers.
   */
  protected void removeFromReadSize(int size) {
    readBufferSize.addAndGet(-size);
  }
  
  /**
   * This should be called when reduceWrite(int size) is called. This removes the size
   * from the total buffer size and adds the write to the clients write stats.
   * 
   * @param size The amount written to the socket.
   */
  protected void removeFromWriteSize(int size) {
    stats.addWrite(size);
    writeBufferSize.addAndGet(-size);
  }
  
  /**
   * This is used to get the current size of the unRead readBuffer.
   * If this is greater then the maxBufferSize the client will be removed
   * from the SocketExecuters reading operations.
   * 
   * @return the current size of the ReadBuffer.
   */
  public int getReadBufferSize() {
    return readBufferSize.get();
  }
  
  /**
   * This is used to get the current size of the unWriten writeBuffer.
   * If this is greater then the maxBufferSize the client will stop accepting
   * new writes (except for forced writes).
   * 
   * @return the current size of the writeBuffer.
   */
  public int getWriteBufferSize() {
    return writeBufferSize.get();
  }
  
  /**
   * This is used to get the currently set max buffer size.
   * 
   * @return the current MaxBuffer size allowed.  The read and write buffer use this independently.
   */
  public int getMaxBufferSize() {
    return maxBufferSize;
  }
  
  /**
   * This is used by the SocketExecuter to set the ThreadExecuter this client is supposed to use.
   * The ThreadExecuter works in a single threaded manor to keep all the client operations in order.
   * 
   * This might need to be exposed to your package but in general You should not be assigning this or
   * changing it.
   *  
   * @param sei the ThreadExecuter to use for this client.
   */
  protected void setThreadExecuter(SubmitterExecutorInterface sei) {
    this.sei = sei;
  }
  
  /**
   * This returns the SubmitterExecutorInterface for this client.
   * This executer works in a single threaded way so blocking this executer is very determinantal to the client flow.
   * 
   * @return the ThreadExecuter for this client.  
   */
  protected SubmitterExecutorInterface getThreadExecuter() {
    return sei;
  }

  /**
   * Sets this client to the specified SocketExecuter.  This happens when the client is added to the SocketExecuter.
   * This method should not be overridden. 
   * 
   * 
   * @param ce the SocketExecuter to set for this client.
   */
  protected void setSocketExecuter(SocketExecuterBase ce) {
    this.ce = ce;
  }
  
  /**
   * This is used to get the clients currently assigned SocketExecuter.
   * 
   * @return the SocketExecuter set for this client. if none, null is returned.
   */
  protected SocketExecuterBase getSocketExecuter() {
    return ce;
  }
  
  /**
   * Used by extending classes to notify the SocketExecuter when this client can Read again.
   */
  protected void flagReadable() {
    if(ce != null) {
      ce.flagNewRead(this);
    }
  }
  
  /**
   * Used by extending classes to notify the SocketExecuter when this client can write again.
   */
  protected void flagWriteable() {
    if(ce != null) {
      ce.flagNewWrite(this);
    }
  }
  
  /**
   * The SocketExecuter calls this method when it detects a socket close event. 
   * This notifies the clients Closer if set.
   */
  protected void callCloser() {
    if(sei != null && closer != null) {
      sei.execute(new Runnable() {
        @Override
        public void run() {
          getCloser().onClose(Client.this);
        }});
    }
  }
  
  /**
   * This is used to get the current Closer for this client.
   * 
   * @return the current Closer interface for this client.
   */
  protected Closer getCloser() {
    return closer;
  }

  /**
   * This sets the Closer interface for this client.  
   * Once set the client will call .onClose on it once it a socket close is detected. 
   * 
   * @param closer sets this clients Closer interface.
   */
  protected void setCloser(Closer closer) {
    if(! closed.get()) {
      this.closer = closer;
    }
  }
  
  /**
   * This is used to notify the clients Reader that a read has been added
   */
  protected void callReader() {
    if(! closed.get() && sei != null && reader != null) {
      sei.execute(new Runnable() {
        @Override
        public void run() {
          getReader().onRead(Client.this);
        }});
    }
  }
  
  /**
   * Returns the currently set Reader callback.
   * 
   * @return the current Reader for this client.
   */
  protected Reader getReader() {
    return reader;
  }

  /**
   * This sets the Reader for the client. 
   * The Reader is called every time a new read is added to the client
   * Each time a Reader is called it should call .getRead() *once* on the client
   * 
   * @param reader the Reader interface to set for this client.
   */
  protected void setReader(Reader reader) {
    if(! closed.get()) {
      this.reader = reader;
    }
  }

  /**
   * This allows you to set/change the max buffer size for this client object.
   * This is the in java memory buffer not the additional socket buffer the OS might setup. 
   * 
   * @param size in bytes.
   */
  public void setMaxBufferSize(int size) {
    if(size > 0) {
      maxBufferSize = size;
    } else {
      throw new IllegalArgumentException("Default size must be more then 0");
    }
  }
  
  /**
   * Whenever a Reader is called for this client a .getRead() should be called.
   * 
   * @return a ByteBuffer of a read for this client.
   */
  public ByteBuffer getRead() {
    ByteBuffer bb = null;
    synchronized(readBuffers) {
      if(readBuffers.remaining() == 0) {
        return null;
      }
      bb = readBuffers.pop();
      removeFromReadSize(bb.remaining());
    }
    if(getReadBufferSize() + bb.remaining() >= maxBufferSize &&  getReadBufferSize() < maxBufferSize) {
      flagReadable();
    }
    return bb;
  }
  
  /**
   * This is for the SocketExecuter.  Once a read is done from the socket this is called.
   * 
   * This call should also make a call to addReadBufferSize(int size).
   * 
   * @param bb the ByteBuffer read off the Socket.
   */
  protected void addReadBuffer(ByteBuffer bb) {
    synchronized(readBuffers) {
      readBuffers.add(bb);
      addToReadSize(bb.remaining());
    }
  }
  
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
  public void writeBlocking(ByteBuffer bb) throws InterruptedException {
    if (bb.hasRemaining()) {
      synchronized (writeBuffers) {
        while(! writeTry(bb) && ! isClosed()) {
          writeBuffers.wait(1000);
        }
      }
    }
  }
  
  /**
   * This write basically forces the client to go over its maxBufferSize.  This technically does not have
   * to be implemented by the client and can be dangerous if done incorrectly.
   * 
   * 
   * @param bb the ByteBuffer to write to the client.
   */
  public void writeForce(ByteBuffer bb) {
    synchronized(writeBuffers) {
        boolean needNotify = ! canWrite();
        this.addToWriteSize(bb.remaining());
        writeBuffers.add(bb.slice());
        if(needNotify) {
          this.flagWriteable();
        }
    }
  }

  /**
   * This is called by the SocketExecuter once the clients socket is able to be written to.
   * It is up to the clients implementer to figure out how to deal with combining or not combining ByteBuffers 
   * for write.  In general its much better to just send the byteBuffers as they where received but the write functions. 
   * 
   * @return a ByteBuffer that can be used to Read new data off the socket for this client.
   */
  protected ByteBuffer getWriteBuffer() {
    synchronized(writeBuffers) {
      //This is to keep from doing a ton of little writes if we can.  We will try to 
      //do at least 8k at a time, and up to 65k if we are already having to combine buffers
      if(currentWriteBuffer == null || currentWriteBuffer.remaining() == 0) {
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
  }
  
  /**
   * This is called after a write is written to the clients socket.  This tells the client how much of that ByteBuffer was written.
   * This should also call removeFromWriteSize(size). to reduce the buffer and update stats.
   * 
   * @param size the size in bytes of data written on the socket.
   */
  protected void reduceWrite(int size) {
    synchronized(writeBuffers) {
      removeFromWriteSize(size);
      if(! currentWriteBuffer.hasRemaining()) {
        currentWriteBuffer = null;
      }
      writeBuffers.notifyAll();
    }
  }
  
  /**
   * Returns the SocketChannel for this client.  If the client does not have a SocketChannel
   * it will return null (ie UDPClient).
   * 
   * @return the SocketChannel for this client.
   */
  public abstract SocketChannel getChannel();
  
  /**
   * This is used by the SocketExecuter to help understand how to manage this client.
   * Currently only UDP and TCP exist.
   * 
   * @return The IP protocol type of this client.
   */
  public abstract WireProtocol getProtocol();
  
  /**
   * Gets the raw Socket object for this Client.
   * 
   * @return the Socket for this client.
   */
  public abstract Socket getSocket();
  
  /**
   * Returns if this client is closed or not.  Once a client is marked closed there is no way to reOpen it.
   * You must just make a new client.
   * 
   * @return true if the client is closed, false if the client is connected.
   */
  public abstract boolean isClosed();
  
  /**
   * closes this client.  Any pending writes when this is called will just be dropped.
   */
  public abstract void close();


  /**
   * A simple byte counter to get rate states from a client. 
   * 
   * @author lwahlmeier
   *
   */
  protected static class ClientByteStats extends SimpleByteStats {
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

