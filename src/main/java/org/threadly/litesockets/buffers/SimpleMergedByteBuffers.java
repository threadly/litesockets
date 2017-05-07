package org.threadly.litesockets.buffers;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.threadly.util.ArgumentVerifier;

/**
 *  This is a lower overhead Implementation of {@link MergedByteBuffers}.
 *  It is not appendable and can only process the buffers it is contructed with.
 * 
 */
public class SimpleMergedByteBuffers extends AbstractMergedByteBuffers {
  
  private final ByteBuffer[] bba;
  private int currentBuffer = 0;
  protected long consumedSize = 0;
  
  public SimpleMergedByteBuffers(boolean readOnly, ByteBuffer ...bbs) {
    super(readOnly);
    for(ByteBuffer bb: bbs) {
      if(bb == null) {
        throw new IllegalArgumentException("Can not add null buffers!");
      }
    }
    if(bbs.length > 0) {
      bba = bbs;
    } else {
      bba = new ByteBuffer[] {EMPTY_BYTEBUFFER};
    }
  }
  
  public SimpleMergedByteBuffers(boolean readOnly, SimpleMergedByteBuffers smbb, ByteBuffer ...bbs) {
    super(readOnly);
    bba = new ByteBuffer[smbb.bba.length-smbb.currentBuffer+ bbs.length];
    int count = 0;
    while(smbb.hasRemaining()) {
      bba[count] = smbb.popBuffer();
      count++;
    }
    for(ByteBuffer bb: bbs) {
      if(bb == null) {
        throw new IllegalArgumentException("Can not add null buffers!");
      }
      bba[count] = bb;
      count++;
    }
  }
  
  private void doGet(final byte[] destBytes) {
    doGet(destBytes, 0, destBytes.length);
  }
  
  private void doGet(final byte[] destBytes, int start, int len) {
    int remainingToCopy = len;

    while (remainingToCopy > 0) {
      final ByteBuffer buf = getNextBuffer();
      final int toCopy = Math.min(buf.remaining(), remainingToCopy);
      buf.get(destBytes, start+destBytes.length - remainingToCopy, toCopy);
      remainingToCopy -= toCopy;
    }
  }
  
  private ByteBuffer getNextBuffer() {
    while(!this.bba[this.currentBuffer].hasRemaining() && bba.length > currentBuffer+1 ) {
      this.bba[this.currentBuffer] = null;
      currentBuffer++;
    }
    if(bba[this.currentBuffer].hasRemaining()) {
      return bba[currentBuffer];
    }
    return EMPTY_BYTEBUFFER;
  }

  @Override
  protected void doAppend(ByteBuffer bb) {
    throw new IllegalStateException("Can not add to this buffer!");
  }

  @Override
  protected void addToFront(ByteBuffer bb) {
    throw new IllegalStateException("Can not add to this buffer!");
  }

  @Override
  public SimpleMergedByteBuffers duplicate() {
    ByteBuffer[] bba2 = new ByteBuffer[bba.length-currentBuffer];
    for(int i=currentBuffer; i<bba.length; i++) {
      bba2[i-currentBuffer] = bba[i].duplicate();
    }
    return new SimpleMergedByteBuffers(markReadOnly, bba2);
  }

  @Override
  public SimpleMergedByteBuffers duplicateAndClean() {
    SimpleMergedByteBuffers smbb = duplicate();
    currentBuffer = bba.length;
    for(int i=0; i<bba.length; i++) {
      bba[i] = null;
    }
    return smbb;
  }

  @Override
  public byte get() {
    ByteBuffer bb = getNextBuffer();
    if(!bb.hasRemaining()){
      throw new BufferUnderflowException();
    }
    if(bb.hasRemaining()) {
      consumedSize++;
      return bb.get();
    }
    return 0;
  }

  @Override
  public void get(byte[] destBytes, int start, int length) {
    ArgumentVerifier.assertNotNull(destBytes, "byte[]");
    if (remaining() < destBytes.length) {
      throw new BufferUnderflowException();
    }
    doGet(destBytes, start, length);
    consumedSize += destBytes.length;
  }

  @Override
  public int nextBufferSize() {
    return getNextBuffer().remaining();
  }

  @Override
  public ByteBuffer popBuffer() {
    ByteBuffer bb = getNextBuffer();
    consumedSize += bb.remaining();
    currentBuffer++;
    return bb.slice();
  }

  @Override
  public int remaining() {
    int left = 0;
    for(int i=currentBuffer; i<bba.length; i++) {
      left+=bba[i].remaining();
    }
    return left;
  }

  @Override
  public boolean hasRemaining() {
    for(int i=currentBuffer; i<bba.length; i++) {
      if(bba[i].hasRemaining()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public ByteBuffer pullBuffer(int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    if (size == 0) {
      return EMPTY_BYTEBUFFER;
    }
    if (remaining() < size) {
      throw new BufferUnderflowException();
    }
    consumedSize += size;
    final ByteBuffer first = getNextBuffer();
    if(first.remaining() == size) {
      currentBuffer++;
      return first.slice();
    } else if(first.remaining() > size) {
      final ByteBuffer bb = first.duplicate().slice();
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
  public void discard(int size) {
    ArgumentVerifier.assertNotNegative(size, "size");
    if (remaining() < size) {
      throw new BufferUnderflowException();
    }
    //We have logic here since we dont need to do any copying and we just drop the bytes
    int toRemoveAmount = size;
    while (toRemoveAmount > 0) {
      final ByteBuffer buf = getNextBuffer();
      final int bufRemaining = buf.remaining();
      if (bufRemaining > toRemoveAmount) {
        buf.position(buf.position() + toRemoveAmount);
        toRemoveAmount = 0;
      } else {
        currentBuffer++;
        toRemoveAmount -= bufRemaining;
      }
    }
    consumedSize += size;
  }
  
  @Override
  protected byte get(int pos) {
    int currentPos = 0;
    for(ByteBuffer bb: this.bba) {
      if(bb != null && bb.remaining() > pos-currentPos) {
        return bb.get(pos-currentPos);
      } else {
        currentPos+=bb.remaining();
      }
    }
    return 0;
  }

  @Override
  public long getTotalConsumedBytes() {
    return this.consumedSize;
  }

  @Override
  public boolean isAppendable() {
    return false;
  }
}
