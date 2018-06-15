package org.threadly.litesockets.buffers;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;

/**
 * This class is used to combine multiple ByteBuffers into 1 simplish to use interface.
 * It provides most of the features of a single ByteBuffer, but with the ability to perform those 
 * operations spanning many ByteBuffers.
 * 
 * The idea here is to keep from having to copy around and merge ByteBuffers as much as possible. 
 * 
 * NOTE: This is not threadSafe.  It should only be accessed by 1 thread at a time.  
 * A single Client's onRead() callback is only called on 1 thread at a time. 
 * 
 */
public class ReuseableMergedByteBuffers extends AbstractMergedByteBuffers {
  
  protected final ArrayDeque<ByteBuffer> availableBuffers = new ArrayDeque<ByteBuffer>(8);
  protected int currentSize;
  protected long consumedSize;

  public ReuseableMergedByteBuffers() {
    this(true);
  }

  public ReuseableMergedByteBuffers(boolean readOnly) {
    super(readOnly);
  }
  
  public ReuseableMergedByteBuffers(boolean readOnly, ByteBuffer ...bbs) {
    super(readOnly);
    add(bbs);
  }
  
  @Override
  protected void doAppend(final ByteBuffer bb) {
    availableBuffers.add(bb);
    currentSize+=bb.remaining();
  }

  @Override
  public ReuseableMergedByteBuffers duplicate() {
    final ReuseableMergedByteBuffers mbb  = new ReuseableMergedByteBuffers(markReadOnly);
    for(final ByteBuffer bb: this.availableBuffers) {
      mbb.add(bb.duplicate());
    }
    return mbb;
  }
  
  @Override
  public ReuseableMergedByteBuffers duplicateAndClean() {
    final ReuseableMergedByteBuffers mbb = new ReuseableMergedByteBuffers(markReadOnly);
    mbb.add(this);
    return mbb;
  }

  @Override
  protected void addToFront(ByteBuffer bb) {
    this.availableBuffers.addFirst(bb.duplicate());
    this.currentSize+=bb.remaining();
  }

  @Override
  public int remaining() {
    return currentSize;
  }
  
  @Override
  public boolean hasRemaining() {
    return currentSize > 0;
  }

  @Override
  public byte get() {
    if(currentSize == 0){
      throw new BufferUnderflowException();
    }
    final ByteBuffer buf = availableBuffers.peek();

    // we assume that we have at least one byte in any available buffers
    final byte result = buf.get();

    if (! buf.hasRemaining()) {
      removeFirstBuffer();
    }
    currentSize--;
    consumedSize++;
    return result;
  }

  @Override
  public int get(byte[] destBytes, int start, int length) {
    ArgumentVerifier.assertNotNull(destBytes, "byte[]");
    if(currentSize == 0) {
      return -1;
    }
    int toCopy = Math.min(length, currentSize); 
    doGet(destBytes, start, toCopy);
    consumedSize += toCopy;
    currentSize -= toCopy;
    return toCopy; 
  }

  @Override
  public int nextBufferSize() {
    if (currentSize == 0) {
      return 0;
    }
    return availableBuffers.peekFirst().remaining();
  }

  @Override
  public ByteBuffer popBuffer() {
    if (currentSize == 0) {
      return IOUtils.EMPTY_BYTEBUFFER;
    }
    return pullBuffer(availableBuffers.peekFirst().remaining());
  }

  @Override
  public ByteBuffer pullBuffer(final int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    if (size == 0) {
      return IOUtils.EMPTY_BYTEBUFFER;
    }
    if (currentSize < size) {
      throw new BufferUnderflowException();
    }
    consumedSize += size;
    currentSize -= size;
    final ByteBuffer first = availableBuffers.peek();
    if(first.remaining() == size) {
      return removeFirstBuffer().slice();
    } else if(first.remaining() > size) {
      final ByteBuffer bb = first.duplicate();
      bb.limit(bb.position()+size);
      first.position(first.position()+size);
      return bb;
    } else {
      final byte[] result = new byte[size];
      doGet(result);
      return ByteBuffer.wrap(result);
    }
  }

  @Override
  public void discard(final int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    if (currentSize < size) {
      throw new BufferUnderflowException();
    }
    //We have logic here since we dont need to do any copying and we just drop the bytes
    int toRemoveAmount = size;
    while (toRemoveAmount > 0) {
      final ByteBuffer buf = availableBuffers.peek();
      final int bufRemaining = buf.remaining();
      if (bufRemaining > toRemoveAmount) {
        buf.position(buf.position() + toRemoveAmount);
        toRemoveAmount = 0;
      } else {
        removeFirstBuffer();
        toRemoveAmount -= bufRemaining;
      }
    }
    consumedSize += size;
    currentSize -= size;
  }

  @Override
  public void discardFromEnd(int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    if (currentSize < size) {
      throw new BufferUnderflowException();
    }
    //We have logic here since we dont need to do any copying and we just drop the bytes
    int toRemoveAmount = size;
    while (toRemoveAmount > 0) {
      final ByteBuffer buf = availableBuffers.peekLast();
      final int bufRemaining = buf.remaining();
      if (bufRemaining > toRemoveAmount) {
        buf.limit(buf.limit() - toRemoveAmount);
        toRemoveAmount = 0;
      } else {
        removeLastBuffer();
        toRemoveAmount -= bufRemaining;
      }
    }
    consumedSize += size;
    currentSize -= size;
  }

  protected ByteBuffer removeFirstBuffer() {
    return this.availableBuffers.pollFirst();
  }

  protected ByteBuffer removeLastBuffer() {
    return this.availableBuffers.pollLast();
  }

  private void doGet(final byte[] destBytes) {
    doGet(destBytes, 0, destBytes.length);
  }
  
  private void doGet(final byte[] destBytes, int start, int len) {
    int remainingToCopy = len;
    while (remainingToCopy > 0) {
      final ByteBuffer buf = availableBuffers.peek();
      final int toCopy = Math.min(buf.remaining(), remainingToCopy);
      buf.get(destBytes, start + len - remainingToCopy, toCopy);
      remainingToCopy -= toCopy;
      if (! buf.hasRemaining()) {
        removeFirstBuffer();
      }
    }
  }
  
  @Override
  public long getTotalConsumedBytes() {
    return consumedSize;
  }

  @Override
  public boolean isAppendable() {
    return true;
  }
  
  @Override
  public String toString() {
    return "MergedByteBuffer size:"+currentSize+": queueSize"+availableBuffers.size()+": consumed:"+consumedSize;
  }

  @Override
  protected byte get(int pos) {
    int currentPos = 0;
    for(ByteBuffer bb: this.availableBuffers) {
      if(bb.remaining() > pos-currentPos) {
        return bb.get(bb.position()+pos-currentPos);
      } else {
        currentPos+=bb.remaining();
      }
    }
    throw new IndexOutOfBoundsException(pos + " > " + (remaining() - 1));
  }
}
