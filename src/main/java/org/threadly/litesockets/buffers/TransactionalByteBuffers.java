package org.threadly.litesockets.buffers;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;


/**
 * 
 * This allows you to used MergedByteBuffers in a transactional way.  This can be useful when reading
 * from the socket using a protocol that is not framed.
 * 
 * NOTE:  If you are modifying the data in the underlying byteArrays that will not be reverted
 * 
 * @author lwahlmeier
 *
 */
public class TransactionalByteBuffers extends ReuseableMergedByteBuffers {
  private static final String ACCESS_ERROR = "Can not call method from different thread then the transaction begain with";
  private final ReentrantLock lock = new ReentrantLock();
  private final ArrayDeque<ByteBuffer> consumedBuffers = new ArrayDeque<ByteBuffer>(8); 
  private int consumedSinceBegin;
  

  public TransactionalByteBuffers() {
    super();
  }
  
  public TransactionalByteBuffers(boolean readOnly) {
    super(readOnly);
  }
  
  /**
   * This mark the beginning of a new transaction.  anything done from this point can either 
   * be committed or rolled back and forgotten.
   * 
   * If an operation is done with out using begin it is committed immediately on completion.
   * 
   */
  public void begin() {
    lock.lock();
    consumedSinceBegin = 0;
    consumedBuffers.clear();
  }
  
  /**
   * Commit a already started transaction.  Until this is done anything pulled out of the consolidator can be reverted.
   * once this happens all changes are permanent and forever.
   * 
   * 
   */
  public void commit() {
    if(!lock.isLocked()) {
      return;
    }
    if (! lock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Must call by same Thread as begin!");
    }
    consumedSinceBegin = 0;
    consumedBuffers.clear();
    lock.unlock();
  }
  
  /**
   * This allows you to roll back all operations done on the consolidator since the begin was called.
   * 
   *  NOTE:  If you are modifying the data in the underlying byteArrays that will not be reverted
   */
  public void rollback() {
    if(!lock.isLocked()) {
      return;
    }
    if (! lock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Must call by same Thread as begin!");
    }
  
    try {
      currentSize += consumedSinceBegin;
      final ByteBuffer firstAvailable = availableBuffers.peek();
      if (firstAvailable != null && firstAvailable.position() != 0) {
        final int firstRollbackAmount = Math.min(consumedSinceBegin, firstAvailable.position());
        firstAvailable.position(firstAvailable.position() - firstRollbackAmount);
        consumedSinceBegin -= firstRollbackAmount;
      }
      while (consumedSinceBegin > 0) {
        final ByteBuffer buf = consumedBuffers.removeLast();
        
        final int rollBackAmount = Math.min(consumedSinceBegin, buf.capacity());
        buf.position(buf.capacity() - rollBackAmount);
        
        availableBuffers.addFirst(buf);
        
        consumedSinceBegin -= rollBackAmount;
      }
      
      if(! consumedBuffers.isEmpty()) {
        throw new IllegalStateException("Problems when trying to roll back ByteBuffers");
      }
    } finally {
      lock.unlock();
    }
  }
  
  @Override
  public byte get() {
    if(lock.isLocked()) {
      if(lock.isHeldByCurrentThread()) {
        final byte b = super.get();
        consumedSinceBegin+=1;
        return b;        
      } else {
        throw new IllegalStateException(ACCESS_ERROR);  
      }
    } else {
      return super.get();
    }
  }
  
  @Override
  public int get(final byte[] destBytes) {
    if(lock.isLocked()) {
      if(lock.isHeldByCurrentThread()) {
        int consumed = super.get(destBytes);
        consumedSinceBegin+=consumed;
        return consumed;
      } else {
        throw new IllegalStateException(ACCESS_ERROR);  
      }
    } else {
      return super.get(destBytes);
    }
  }
  
  @Override
  public ByteBuffer pullBuffer(final int size) {
    if(lock.isLocked()) {
      if(lock.isHeldByCurrentThread()) {
        final ByteBuffer bb = super.pullBuffer(size);
        consumedSinceBegin+=size;
        return bb;
      } else {
        throw new IllegalStateException(ACCESS_ERROR);  
      }
    } else {
      return super.pullBuffer(size);
    }
  }
  
  @Override
  public void discard(final int size) {
    if(lock.isLocked()) {
      if(lock.isHeldByCurrentThread()) {
        super.discard(size);
        consumedSinceBegin+=size;        
      } else {
        throw new IllegalStateException(ACCESS_ERROR);  
      }
    } else {
      super.discard(size);
    }
  }
  
  @Override
  protected void doAppend(final ByteBuffer bb) {
    // TODO - need to slice due to rollback logic requiring an absolute position of zero
    super.doAppend(bb.slice());
  }

  @Override
  protected ByteBuffer removeFirstBuffer() {
    final ByteBuffer bb = super.removeFirstBuffer();
    if(lock.isLocked() && lock.isHeldByCurrentThread()) {
      this.consumedBuffers.add(bb);
    }
    return bb;
  }

}