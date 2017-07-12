package org.threadly.litesockets.buffers;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * This class is used to combine multiple ByteBuffers into 1 simplish to use interface.
 * It provides most of the features of a single ByteBuffer, but with the ability to perform those 
 * operations spanning many ByteBuffers.
 * 
 * The idea here is to keep from having to copy around and merge ByteBuffers as much as possible. 
 * 
 * NOTE: This is not threadSafe.  It should only be accessed by 1 thread at a time.
 * 
 */
public interface MergedByteBuffers {
  int BYTES_IN_LONG = Long.SIZE/Byte.SIZE;
  int BYTES_IN_INT = Integer.SIZE/Byte.SIZE;
  int BYTES_IN_SHORT = Short.SIZE/Byte.SIZE;

  short UNSIGNED_BYTE_MASK = 0xff;
  int UNSIGNED_SHORT_MASK = 0xffff;
  long UNSIGNED_INT_MASK = 0xffffffffL;
  
  /**
   * This method allows you to add ByteBuffers to the MergedByteBuffers.  
   * All must be done in order of how you want to pull the data back out.
   * 
   * @param ba - The byte[] to add to the MergedByteBuffers
   */
  public void add(final byte[] ...ba);
  
  /**
   * This method allows you to add ByteBuffers to the MergedByteBuffers.  
   * All must be done in order of how you want to pull the data back out.
   * 
   * @param buffer - The byte buffer to add to the MergedByteBuffers
   */
  public void add(final ByteBuffer ...buffer);
  
  /**
   * This method allows you to add a MergedByteBuffers to another MergedByteBuffers.  
   * All must be done in order of how you want to pull the data back out.
   * 
   * @param mbb - The MergedByteBuffers to put into this MergedByteBuffers
   */
  public void add(final MergedByteBuffers ...mbb);
  
  /**
   * Make a complete duplicate of this MergedByteBuffer.  Both references should function independently, but
   * they are still using the same ByteBuffer backing arrays so any change to the actual byte[] in the 
   * backing ByteBuffers will change in both.
   * 
   * @return a new MergedByteBuffers object that duplicates this one, but works independently.
   */
  public MergedByteBuffers duplicate();
  
  /**
   * This will flush all the data in this MergedByteBuffer into another MergedByteBuffer.
   * 
   * @return a new MergedByteBuffer with the data that was in the original one.
   */
  public MergedByteBuffers duplicateAndClean();

  /**
   * Like the indexOf in String object this find a pattern of bytes and reports the position they start at.  
   * This defaults to using US-ASCII as the Charset.
   * 
   * @param pattern String pattern to search for.
   * @return an {@code int} with the offset of the first occurrence of the given . 
   */
  public int indexOf(final String pattern);
  /**
   * Like the indexOf in String object this find a pattern of bytes and reports the position they start at.
   * 
   * @param pattern String pattern to search for.
   * @param charSet the Charset of the string.
   * @return an {@code int} with the offset of the first occurrence of the given . 
   */
  public int indexOf(final String pattern, final Charset charSet);
  
  /**
   * Like the indexOf in String object this find a pattern of bytes and reports the position they start at.
   * 
   * @param pattern byte[] pattern to search for
   * @return an {@code int} with the offset of the first occurrence of the given . 
   */
  public int indexOf(final byte[] pattern);

  /**
   * Like the indexOf in String object this find a pattern of bytes and reports the position they start at.  
   * This defaults to using US-ASCII as the Charset.
   * 
   * @param pattern String pattern to search for.
   * @param fromPosition Starting index to search from, inclusive
   * @return an {@code int} with the offset of the first occurrence of the given . 
   */
  public int indexOf(final String pattern, int fromPosition);

  /**
   * Like the indexOf in String object this find a pattern of bytes and reports the position they start at.
   * 
   * @param pattern String pattern to search for.
   * @param charSet the Charset of the string.
   * @param fromPosition Starting index to search from, inclusive
   * @return an {@code int} with the offset of the first occurrence of the given . 
   */
  public int indexOf(final String pattern, final Charset charSet, int fromPosition);

  /**
   * Like the indexOf in String object this find a pattern of bytes and reports the position they start at.
   * 
   * @param pattern byte[] pattern to search for
   * @param fromPosition Starting index to search from, inclusive
   * @return an {@code int} with the offset of the first occurrence of the given . 
   */
  public int indexOf(final byte[] pattern, int fromPosition);

  /**
   * Check how much data is available in the MergedByteBuffer.
   * 
   * @return the current about of space remaining in the MergedByteBuffer.
   */
  public int remaining();
  
  /**
   * <p>Returns if there is any data pending in this MergedByteBuffer or not.
   * This can be more efficient then calling {@link #remaining()} on some implementations
   * of MergedByteBuffers.</p>
   * 
   * @return the current about of space remaining in the MergedByteBuffers.
   */
  public boolean hasRemaining();

  /**
   * Returns the next byte stored in the MergedByteBuffers.
   * 
   * @return the next single Byte from the MergedByteBuffers.
   */
  public byte get();

  /**
   * Returns the next {@code byte} unsigned as {@code short} stored in the MergedByteBuffers.
   * 
   * @return the next single unsigned {@code byte} as {@code short} from the MergedByteBuffers.
   */
  public short getUnsignedByte();

  /**
   * Returns an unsigned {@code short} (as an {@code int}) from the next 2 stored bytes.
   * 
   * @return the next 2 byte as an {@code int} (unsigned Short)
   */
  public int getUnsignedShort();

  /**
   * Returns the next 2 bytes as a {@code short} value.
   * 
   * @return {@code short} of the next 2 bytes.
   */
  public short getShort();

  /**
   * Returns the next 4 bytes as an {@code int} value.
   * 
   * @return an {@code int} from the next 4 bytes
   */
  public int getInt();

  /**
   * Returns an unsigned short (as an {@code int}) from the next 2 stored bytes.
   * 
   * @return the next 2 byte as an {@code int} (unsigned Short)
   */
  public long getUnsignedInt();

  /**
   * Returns the next 8 bytes as a {@code long} value.
   * 
   * @return a {@code long} from the next 8 bytes.
   */
  public long getLong();


  /**
   * Fills the passed {@code byte[]} completely with data from the MergedByteBuffer. 
   * 
   * @param destBytes fills the given byteArray with the next bytes from the MergedByteBuffer.
   *
   * @return number of bytes copied into destBytes.
   */
  public int get(final byte[] destBytes);
  
  /**
   * Fills the passed {@code byte[]} completely with data from the MergedByteBuffer. 
   * 
   * @param destBytes fills the given byteArray with the next bytes from the MergedByteBuffer.
   * @param start starting position to fill in the byte[].
   * @param length how much data to copy into the buffer.
   * 
   * @return number of bytes copied into destBytes.
   */
  public int get(final byte[] destBytes, int start, int length);

  /**
   * Get the size of the next full {@link ByteBuffer} in the queue.
   * 
   * @return the size of the next {@link ByteBuffer} in the queue.
   */
  public int nextBufferSize();

  /**
   * Get the next Complete {@link ByteBuffer} in its entirety.  This byteBuffer could be 
   * any size and it will just pull it off the queue and return it.
   * 
   * If {@link #remaining()} is 0 you will get an empty {@link ByteBuffer}.
   * 
   * @return the next {@link ByteBuffer} in the queue.
   */
  public ByteBuffer popBuffer();

  /**
   * 
   * @param size size of the {@link ByteBuffer} to pull out of the MergedByteBuffer.
   * 
   * @return a {@link ByteBuffer} of %SIZE% bytes.
   */
  public ByteBuffer pullBuffer(final int size);

  /**
   * Discard will drop how ever many bytes you tell it to on the floor.
   * 
   * @param size the number of bytes to discard.
   */
  public void discard(final int size);

  /**
   * Similar to {@link #discard(int)} except that the bytes removed from this will be from the end 
   * of the buffer and discard towards the head.
   * 
   * @param size the number of bytes to discard.
   */
  public void discardFromEnd(final int size);

  /**
   * This will return the specified number of bytes as a String object.
   * This will default to using the US-ASCII Charset.
   * 
   * @param size the number of bytes to put into the string.
   * @return as String Object with set number of bytes in it.
   */  
  public String getAsString(final int size);

  /**
   * This will return the specified number of bytes as a String object.
   * 
   * @param size the number of bytes to put into the string.
   * @param charSet the {@link Charset} of the String to return.
   * @return as String Object with set number of bytes in it.
   */
  public String getAsString(final int size, final Charset charSet);

  /**
   * <p>This will return the number of bytes that has been consumed by this MergedByteBuffer.</p>
   * 
   * @return The total number of bytes that haven been pushed through this MergedByteBuffer.
   */
  public long getTotalConsumedBytes();
  
  /**
   * @return true if ByteByffers can be added to this {@link MergedByteBuffers} or false if they can not be.
   */
  public boolean isAppendable(); 
  
  public InputStream asInputStream();
}
