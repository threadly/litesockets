package org.threadly.litesockets.utils;

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
public class TransactionalByteBuffers extends MergedByteBuffers {
  private static final String ACCESS_ERROR = "Can not call method from different thread then the transaction begain with";
  private final ReentrantLock lock = new ReentrantLock();
  private final ArrayDeque<ByteBuffer> consumedBuffers = new ArrayDeque<ByteBuffer>(8); 
  private int consumedSinceBegin = 0;
  
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
      ByteBuffer firstAvailable = availableBuffers.peek();
      if (firstAvailable != null && firstAvailable.position() != 0) {
        int firstRollbackAmount = Math.min(consumedSinceBegin, firstAvailable.position());
        firstAvailable.position(firstAvailable.position() - firstRollbackAmount);
        consumedSinceBegin -= firstRollbackAmount;
      }
      while (consumedSinceBegin > 0) {
        ByteBuffer buf = consumedBuffers.removeLast();
        
        int rollBackAmount = Math.min(consumedSinceBegin, buf.capacity());
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
    if(! lock.isLocked()) {
      return super.get();
    } else if(lock.isHeldByCurrentThread()) {
      byte b = super.get();
      consumedSinceBegin+=1;
      return b;
    } else {
      throw new IllegalStateException(ACCESS_ERROR);
    }
  }
  
  @Override
  public void get(byte[] destBytes) {
    if(! lock.isLocked()) {
      super.get(destBytes);
    } else if(lock.isHeldByCurrentThread()) {
      super.get(destBytes);
      consumedSinceBegin+=destBytes.length;
    } else {
      throw new IllegalStateException(ACCESS_ERROR);
    }
  }
  
  @Override
  public ByteBuffer pop() {
    return super.pop();
  }
  
  @Override
  public ByteBuffer pull(int size) {
    if(! lock.isLocked()) {
      return super.pull(size);
    } else if(lock.isHeldByCurrentThread()) {
      ByteBuffer bb = super.pull(size);
      consumedSinceBegin+=size;
      return bb;
    } else {
      throw new IllegalStateException(ACCESS_ERROR);
    }
  }
  
  @Override
  public void discard(int size) {
    if(! lock.isLocked()) {
      super.discard(size);
    } else if(lock.isHeldByCurrentThread()) {
      super.discard(size);
      consumedSinceBegin+=size;
    } else {
      throw new IllegalStateException(ACCESS_ERROR);
    }
  }
  
  @Override
  public String getAsString(int size) {
    return super.getAsString(size);
  }


  @Override
  protected ByteBuffer removeFirstBuffer() {
    ByteBuffer bb = super.removeFirstBuffer();
    if(lock.isLocked() && lock.isHeldByCurrentThread()) {
      this.consumedBuffers.add(bb);
    }
    return bb;
  }

}
