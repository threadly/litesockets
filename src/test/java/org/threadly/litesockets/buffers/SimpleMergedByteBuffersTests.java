package org.threadly.litesockets.buffers;

import static org.junit.Assert.*;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.After;
import org.junit.Test;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.buffers.SimpleMergedByteBuffers;

public class SimpleMergedByteBuffersTests {
  
  @After
  public void stop() {
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
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
  public void searchSpaning() {
    SimpleMergedByteBuffers mbb = new SimpleMergedByteBuffers(false,
    ByteBuffer.wrap("vsdljsakd".getBytes()),
    ByteBuffer.wrap("testingC".getBytes()),
    ByteBuffer.wrap("test".getBytes()),
    ByteBuffer.wrap("ingCrap".getBytes()));

    assertEquals(17, mbb.indexOf("testingCrap"));
    mbb.discard(17);
    assertEquals("testingCrap", mbb.getAsString("testingCrap".length()));
    
  }

  @Test
  public void getInts() {
    
    ByteBuffer[] bba = new ByteBuffer[200]; 
    for(int i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(4);
      bb.putInt(i);
      bb.flip();
      bba[i] = bb;
    }
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bba);
    
    for(int i = 0; i<200; i++) {
      assertEquals(i, mbb.getInt());
    }
    assertEquals(200*4, mbb.getTotalConsumedBytes());
  }

  @Test
  public void getShorts() {
    ByteBuffer[] bba = new ByteBuffer[200]; 
    for(short i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(10);
      bb.putShort(i);
      bb.flip();
      bba[i] = bb;
    }
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bba);
    for(short i = 0; i<200; i++) {
      assertEquals(i, mbb.getShort());
    }
    assertEquals(200*2, mbb.getTotalConsumedBytes());
  }

  @Test
  public void getLongs() {
    ByteBuffer[] bba = new ByteBuffer[200]; 
    for(int i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(20);
      bb.position(5);
      bb.putLong(i);
      bb.position(5);
      bb.limit(13);
      bba[i] = bb;
    }
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bba);
    for(long i = 0; i<200; i++) {
      assertEquals(i, mbb.getLong());
    }
    assertEquals(200*8, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void getLongOverSpan() {
    ByteBuffer[] bba = new ByteBuffer[100];
    for(byte i = 0; i<100; i++) {
      ByteBuffer bb = ByteBuffer.allocate(1);
      bb.put(i);
      bb.flip();
      bba[i] = bb;
    }
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bba);
    System.out.println(mbb.remaining());
    
    assertEquals(283686952306183L, mbb.getLong());
    assertEquals(579005069656919567L, mbb.getLong());
    assertEquals(100-8-8, mbb.remaining());
    assertEquals(16, mbb.getTotalConsumedBytes());
  }

  @Test
  public void getByteUnsigned() {
    ByteBuffer bb = ByteBuffer.allocate(1);
    bb.put((byte)-1);
    bb.flip();
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bb);
    assertEquals(255, mbb.getUnsignedByte());
  }
  
  @Test
  public void getShortUnsigned() {
    ByteBuffer bb = ByteBuffer.allocate(2);
    bb.putShort((short)-1);
    bb.flip();
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bb);
    assertEquals(65535, mbb.getUnsignedShort());
  }
  
  @Test
  public void getBytes() {
    ByteBuffer bb = ByteBuffer.allocate(200);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bb);
    for(byte i = 0; i<100; i++) {
      assertEquals(i, mbb.get());
    }
    assertEquals(100, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void byteSearch() {
    String text = "FindMe";

    ByteBuffer bb = ByteBuffer.allocate(500);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.put(text.getBytes());
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bb);
    assertEquals(100, mbb.indexOf(text));
    assertEquals(-1, mbb.indexOf(text+"3"));
    assertEquals(100, mbb.indexOf(text.getBytes()));
    mbb.discard(100);
    assertEquals(text, mbb.getAsString(text.getBytes().length));
    assertEquals(100+text.getBytes().length, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void getUnsignedInt() {
    ByteBuffer bb = ByteBuffer.allocate(4);
    bb.putInt(Integer.MAX_VALUE+500);
    bb.flip();
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bb);
    long value = (Integer.MAX_VALUE+500 & 0xFFFFFFFFL);
    System.out.println(value);
    assertEquals(value, mbb.getUnsignedInt());
  }
  
  @Test
  public void pullBytes() {
    ByteBuffer[] bba = new ByteBuffer[100];
    for(byte i = 0; i<100; i++) {
      ByteBuffer bb = ByteBuffer.allocate(1);
      bb.put(i);
      bb.flip();
      bba[i] = bb;
    }
    SimpleMergedByteBuffers mbb = new SimpleMergedByteBuffers(false, bba);
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
  }
  
  @Test
  public void pullZero() {
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false);
    assertEquals(0, mbb.pullBuffer(0).remaining());
    assertEquals(0, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void popZeroBuffer() {
    SimpleMergedByteBuffers mbb = new SimpleMergedByteBuffers(false);
    assertEquals(0, mbb.nextBufferSize());
    assertEquals(0, mbb.popBuffer().remaining());
  }
  
  @Test
  public void popBuffer() {

    Random rnd = new Random();
    int size = Math.abs(rnd.nextInt(300))+10;
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, ByteBuffer.allocate(size), ByteBuffer.allocate(rnd.nextInt(300)), ByteBuffer.allocate(rnd.nextInt(300)), ByteBuffer.allocate(rnd.nextInt(300)));
    assertEquals(size, mbb.nextBufferSize());
    assertEquals(size, mbb.popBuffer().remaining());
    assertEquals(size, mbb.getTotalConsumedBytes());
  }
  
  @Test
  public void discardAllBuffers() {
    Random rnd = new Random();
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false, ByteBuffer.allocate(rnd.nextInt(300)), ByteBuffer.allocate(rnd.nextInt(300)), ByteBuffer.allocate(rnd.nextInt(300)));
    int size = mbb.remaining();
    mbb.discard(size);
    assertEquals(0, mbb.remaining());
    assertEquals(size, mbb.getTotalConsumedBytes());
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badArrayGet() {
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false);
    mbb.get(new byte[100]);
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void discardUnderFlow() {
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false);
    mbb.discard(100);
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void badArrayGet2() {
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false);
    mbb.get(null);
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badInt() {
    MergedByteBuffers mbb = new SimpleMergedByteBuffers(false);
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