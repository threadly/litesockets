package org.threadly.litesockets.utils;

import static org.junit.Assert.*;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.junit.Test;

public class MergedByteBufferTests {

  @Test
  public void getInts() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    for(int i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(4);
      bb.putInt(i);
      bb.flip();
      mbb.add(bb);
    }

    for(int i = 0; i<200; i++) {
      assertEquals(i, mbb.getInt());
    }
  }

  @Test
  public void getShorts() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    for(short i = 0; i<200; i++) {
      ByteBuffer bb = ByteBuffer.allocate(10);
      bb.putShort(i);
      bb.flip();
      mbb.add(bb);
    }
    for(short i = 0; i<200; i++) {
      assertEquals(i, mbb.getShort());
    }
  }

  @Test
  public void getLongs() {
    MergedByteBuffers mbb = new MergedByteBuffers();
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
  }
  
  @Test
  public void getLongOverSpan() {
    MergedByteBuffers mbb = new MergedByteBuffers();
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
  }

  @Test
  public void getBytes() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    ByteBuffer bb = ByteBuffer.allocate(200);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    mbb.add(bb);
    for(byte i = 0; i<100; i++) {
      assertEquals(i, mbb.get());
    }
  }
  
  @Test
  public void byteSearch() {
    String text = "FindMe";
    MergedByteBuffers mbb = new MergedByteBuffers();
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
  }
  
  @Test
  public void getUnsignedInt() {
    MergedByteBuffers mbb = new MergedByteBuffers();
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
    MergedByteBuffers mbb = new MergedByteBuffers();
    for(byte i = 0; i<100; i++) {
      ByteBuffer bb = ByteBuffer.allocate(1);
      bb.put(i);
      bb.flip();
      mbb.add(bb);
    }
    ByteBuffer stuff = mbb.pull(20);
    for(int i=0; i<20; i++) {
      assertEquals(i, stuff.get());
    }

    for(int i=20; i<100; i++) {
      stuff = mbb.pull(1);
      assertEquals(i, stuff.get());
    }
    
    ByteBuffer bb = ByteBuffer.allocate(100);
    for(byte i = 0; i<100; i++) {
      bb.put(i);
    }
    bb.flip();
    mbb.add(bb);
    stuff = mbb.pull(4);
    assertEquals(66051, stuff.getInt());
  }
  
  @Test
  public void pullZero() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    assertEquals(0, mbb.pull(0).remaining());
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badArrayGet() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    mbb.get(new byte[100]);
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void badArrayGet2() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    mbb.get(null);
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badInt() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    mbb.getInt();
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badLong() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    mbb.getLong();
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badShort() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    mbb.getShort();
  }
  
  @Test(expected=BufferUnderflowException.class)
  public void badPull() {
    MergedByteBuffers mbb = new MergedByteBuffers();
    mbb.pull(10);
  }
}