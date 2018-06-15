package org.threadly.litesockets.buffers;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.After;
import org.junit.Test;
import org.threadly.util.StringUtils;

public class ReuseableMergedByteBuffersTests {
  
  @After
  public void stop() {
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test
  public void constructWithBuffersTest() {
    ByteBuffer bb = ByteBuffer.wrap("vsdljsakd".getBytes());
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers(false, bb);
    assertEquals(bb.remaining(), mbb.remaining());
    while (mbb.hasRemaining()) {
      assertEquals(bb.get(), mbb.get());
    }
  }
  
  @Test
  public void addMergedByteBuffersWithLimitTest() {
    int size = 256;
    ByteBuffer bb = ByteBuffer.wrap(StringUtils.makeRandomString(size).getBytes());
    MergedByteBuffers mbbOut = new ReuseableMergedByteBuffers(false, bb);
    MergedByteBuffers mbbIn = new ReuseableMergedByteBuffers(false);

    assertEquals(size, mbbOut.remaining());
    mbbIn.add(mbbOut, size / 2);
    assertEquals(size / 2, mbbOut.remaining());
    assertEquals(size / 2, mbbIn.remaining());

    mbbIn.add(mbbOut, size / 2);
    assertEquals(0, mbbOut.remaining());
    assertEquals(size, mbbIn.remaining());
  }
  
  @Test
  public void indexPatternTest() {
    String st = "HTTP/1.1 101 Switching Protocols\r\nAccept: */*\r\nSec-WebSocket-Accept: W5bRv0dwYtd1GPxLJnXACYizcbU=\r\nUser-Agent: litesockets\r\n\r\n";
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.add(st.getBytes());
    assertEquals("HTTP/1.1 101 Switching Protocols", mbb.getAsString(mbb.indexOf("\r\n")));
    mbb.discard(2);
    assertEquals(88, mbb.indexOf("\r\n\r\n"));
  }
  
  @Test
  public void indexOfHalfMatchTest() {
    String pattern = "123";
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers(false, ByteBuffer.wrap(("foobarthelongversion" + pattern).getBytes()));
    assertEquals(-1, mbb.indexOf(pattern + pattern));
  }
  
  @Test
  public void GetArrayOffset() throws IOException {
    String st = "HTTP/1.1 101 Switching Protocols\r\nAccept: */*\r\nSec-WebSocket-Accept: W5bRv0dwYtd1GPxLJnXACYizcbU=\r\nUser-Agent: litesockets\r\n\r\n";
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.add(st.getBytes());
    byte[] ba = new byte[mbb.remaining()];
    System.out.println(mbb.remaining());
    for(int i=0; i<ba.length; i++) {
      mbb.get(ba, i, 1);
    }
    System.out.println(new String(ba).length());
    System.out.println(mbb.remaining());
    assertEquals(st, new String(ba));
  }
  
  @Test
  public void searchSpaning() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.add(ByteBuffer.wrap("vsdljsakd".getBytes()));
    mbb.add(ByteBuffer.wrap("testingC".getBytes()));
    mbb.add(ByteBuffer.wrap("test".getBytes()));
    mbb.add(ByteBuffer.wrap("ingCrap".getBytes()));
    System.out.println(mbb.indexOf("testingCrap"));
    assertEquals(17, mbb.indexOf("testingCrap"));
    mbb.discard(17);
    assertEquals("testingCrap", mbb.getAsString("testingCrap".length())); 
  }
  
  @Test
  public void getIndex() {
    int count = 10;
    byte[] bytes1 = new byte[count];
    byte[] bytes2 = new byte[count];
    for (int i = 0; i < count; i++) {
      if (i < count / 2) {
        bytes1[i] = (byte)i;
        bytes2[i] = -1;
      } else {
        bytes1[i] = -1;
        bytes2[i] = (byte)i;
      }
    }
    ByteBuffer bb1 = ByteBuffer.wrap(bytes1);
    bb1.limit(count / 2);
    ByteBuffer bb2 = ByteBuffer.wrap(bytes2);
    bb2.position(count / 2);
    ReuseableMergedByteBuffers mbb = new ReuseableMergedByteBuffers(false, bb1, bb2);
    for (int i = 0; i < count; i++) {
      assertEquals((byte)i, mbb.get(i));
    }
  }

  @Test
  public void getInts() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    for(int i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(4);
      bb.putInt(i);
      bb.flip();
      mbb.add(bb);
    }

    for(int i = 0; i<200; i++) {
      assertEquals(i, mbb.getInt());
    }
    assertEquals(200*4, mbb.getTotalConsumedBytes());
  }

  @Test
  public void getShorts() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    for(short i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(10);
      bb.putShort(i);
      bb.flip();
      mbb.add(bb);
    }
    for(short i = 0; i<200; i++) {
      assertEquals(i, mbb.getShort());
    }
    assertEquals(200*2, mbb.getTotalConsumedBytes());
  }

  @Test
  public void getLongs() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    for(long i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(20);
      bb.position(5);
      bb.putLong(i);
      bb.position(5);
      bb.limit(13);
      mbb.add(bb);
    }
    for(long i = 0; i<200; i++) {
      assertEquals(i, mbb.getLong());
    }
    assertEquals(200*8, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void getLongOverSpan() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    for(byte i = 0; i<100; i++) {
      ByteBuffer bb = ByteBuffer.allocate(1);
      bb.put(i);
      bb.flip();
      mbb.add(bb);
    }
    System.out.println(mbb.remaining());
    
    assertEquals(283686952306183L, mbb.getLong());
    assertEquals(579005069656919567L, mbb.getLong());
    assertEquals(100-8-8, mbb.remaining());
    assertEquals(16, mbb.getTotalConsumedBytes());
  }

  @Test
  public void getByteUnsigned() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    ByteBuffer bb = ByteBuffer.allocate(1);
    bb.put((byte)-1);
    bb.flip();
    mbb.add(bb);
    assertEquals(255, mbb.getUnsignedByte());
  }
  
  @Test
  public void getShortUnsigned() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    ByteBuffer bb = ByteBuffer.allocate(2);
    bb.putShort((short)-1);
    bb.flip();
    mbb.add(bb);
    assertEquals(65535, mbb.getUnsignedShort());
  }
  
  @Test
  public void getBytes() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    ByteBuffer bb = ByteBuffer.allocate(200);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    mbb.add(bb);
    for(byte i = 0; i<100; i++) {
      assertEquals(i, mbb.get());
    }
    assertEquals(100, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void byteSearch() {
    String text = "FindMe";
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    ByteBuffer bb = ByteBuffer.allocate(500);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.put(text.getBytes());
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    mbb.add(bb);
    assertEquals(100, mbb.indexOf(text));
    assertEquals(-1, mbb.indexOf(text+"3"));
    assertEquals(100, mbb.indexOf(text.getBytes()));
    mbb.discard(100);
    assertEquals(text, mbb.getAsString(text.getBytes().length));
    assertEquals(100+text.getBytes().length, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void getUnsignedInt() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    ByteBuffer bb = ByteBuffer.allocate(4);
    bb.putInt(Integer.MAX_VALUE+500);
    bb.flip();
    mbb.add(bb);
    long value = (Integer.MAX_VALUE+500 & 0xFFFFFFFFL);
    System.out.println(value);
    assertEquals(value, mbb.getUnsignedInt());
  }
  
  @Test
  public void pullBytes() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    for(byte i = 0; i<100; i++) {
      ByteBuffer bb = ByteBuffer.allocate(1);
      bb.put(i);
      bb.flip();
      mbb.add(bb);
    }
    ByteBuffer stuff = mbb.pullBuffer(20);
    for(int i=0; i<20; i++) {
      assertEquals(i, stuff.get());
    }

    for(int i=20; i<100; i++) {
      stuff = mbb.pullBuffer(1);
      assertEquals(i, stuff.get());
    }
    
    ByteBuffer bb = ByteBuffer.allocate(100);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    mbb.add(bb);
    stuff = mbb.pullBuffer(4);
    assertEquals(66051, stuff.getInt());
    assertEquals(104, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void pullZero() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    assertEquals(0, mbb.pullBuffer(0).remaining());
    assertEquals(0, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void popZeroBuffer() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    assertEquals(0, mbb.nextBufferSize());
    assertEquals(0, mbb.popBuffer().remaining());
  }
  
  @Test
  public void popBuffer() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    Random rnd = new Random();
    int size = Math.abs(rnd.nextInt(300))+10;
    ByteBuffer bb = ByteBuffer.allocate(size);
    mbb.add(bb);
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    assertEquals(size, mbb.nextBufferSize());
    assertEquals(size, mbb.popBuffer().remaining());
    assertEquals(size, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void discardAllBuffers() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    Random rnd = new Random();
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    int size = mbb.remaining();
    mbb.discard(size);
    assertEquals(0, mbb.remaining());
    assertEquals(size, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void discardAllFromEndBuffers() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    Random rnd = new Random();
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    int size = mbb.remaining();
    mbb.discardFromEnd(size);
    assertEquals(0, mbb.remaining());
    assertEquals(size, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void discardHalfFromEndBuffers() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    Random rnd = new Random();
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    mbb.add(ByteBuffer.allocate(rnd.nextInt(300)));
    if (mbb.remaining() % 2 == 1) {
      // test only works with an even sized array due to integer division
      mbb.add(new byte[] { 0x0 });
    }
    
    MergedByteBuffers expectedStart = mbb.duplicate();
    int size = mbb.remaining();
    mbb.discardFromEnd(size / 2);
    
    assertEquals(size / 2, mbb.remaining());
    assertEquals(size / 2, mbb.getTotalConsumedBytes());
    while (mbb.hasRemaining()) {
      assertEquals(expectedStart.get(), mbb.get());
    }
  }
  
  @Test
  public void badArrayGet() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    assertEquals(-1, mbb.get(new byte[100]));
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void discardUnderFlow() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.discard(100);
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void discardFromEndUnderFlow() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.discardFromEnd(100);
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void badArrayGet2() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.get(null);
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badInt() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.getInt();
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badLong() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.getLong();
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badShort() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.getShort();
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badPull() {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers();
    mbb.pullBuffer(10);
  }
}
