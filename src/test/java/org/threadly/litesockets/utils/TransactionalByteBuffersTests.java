package org.threadly.litesockets.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.test.concurrent.TestCondition;

public class TransactionalByteBuffersTests {
  PriorityScheduler PS = new PriorityScheduler(5);

  @Test
  public void simpleGetTest() {
    String s = "TEST1234567890";
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.begin();
    for(int i=0; i<s.getBytes().length; i++) {
      assertEquals(s.getBytes()[i], tbb.get());
    }
    tbb.rollback();
    tbb.begin();
    for(int i=0; i<s.getBytes().length; i++) {
      assertEquals(s.getBytes()[i], tbb.get());
    }
    tbb.rollback();
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.get(); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    
    assertTrue(hitException.get());
    tbb.commit();
    
    for(int i=0; i<s.getBytes().length; i++) {
      assertEquals(s.getBytes()[i], tbb.get());
    }
  }
  
  @Test
  public void getArrayTest() {
    String s = "TEST1234567890";
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.begin();
    byte[] ba = new byte[4];
    tbb.get(ba);
    assertEquals("TEST", new String(ba));
    tbb.get(ba);
    assertEquals("1234", new String(ba));
    tbb.get(ba);
    assertEquals("5678", new String(ba));
    tbb.get(ba);
    assertEquals("90TE", new String(ba));
    tbb.rollback();

    tbb.begin();
    tbb.get(ba);
    assertEquals("TEST", new String(ba));
    tbb.get(ba);
    assertEquals("1234", new String(ba));
    tbb.get(ba);
    assertEquals("5678", new String(ba));
    tbb.get(ba);
    assertEquals("90TE", new String(ba));
    tbb.commit();
    
    tbb.get(ba);
    assertEquals("ST12", new String(ba));
    
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          byte[] ba2 = new byte[4];
          tbb.get(ba2); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.rollback();
    ba = new byte[8];
    tbb.get(ba);
    assertEquals("34567890", new String(ba));
    ba = new byte[14];
    tbb.begin();
    tbb.get(ba);
    assertEquals(s, new String(ba));
    tbb.commit();
    tbb.get(ba);
    assertEquals(s, new String(ba));
    assertEquals(0, tbb.remaining());
  }
  
  @Test
  public void getShortTest() {
    ByteBuffer bb = ByteBuffer.allocate(20);
    for(int i=0; i<10; i++) {
      bb.putShort((short)i);
    }
    bb.flip();
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(bb.duplicate());
    tbb.add(bb.duplicate());
    tbb.begin();
    for(int i=0; i<5; i++) {
      assertEquals((short)i, tbb.getShort());
    }
    tbb.rollback();
    for(int i=0; i<5; i++) {
      assertEquals((short)i, tbb.getShort());
    }
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.getShort(); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    for(int i=5; i<10; i++) {
      assertEquals((short)i, tbb.getShort());
    }
    for(int i=0; i<10; i++) {
      assertEquals((short)i, tbb.getShort());
    }
    assertEquals(0, tbb.remaining());
  }
  
  @Test
  public void getIntTest() {
    ByteBuffer bb = ByteBuffer.allocate(40);
    for(int i=0; i<10; i++) {
      bb.putInt(i);
    }
    bb.flip();
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(bb.duplicate());
    tbb.add(bb.duplicate());
    tbb.begin();
    for(int i=0; i<5; i++) {
      assertEquals(i, tbb.getInt());
    }
    tbb.rollback();
    for(int i=0; i<5; i++) {
      assertEquals(i, tbb.getInt());
    }
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.getInt(); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    for(int i=5; i<10; i++) {
      assertEquals(i, tbb.getInt());
    }
    for(int i=0; i<10; i++) {
      assertEquals(i, tbb.getInt());
    }
    assertEquals(0, tbb.remaining());
  }
  
  @Test
  public void getLongTest() {
    ByteBuffer bb = ByteBuffer.allocate(80);
    for(int i=0; i<10; i++) {
      bb.putLong((long)i);
    }
    bb.flip();
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(bb.duplicate());
    tbb.add(bb.duplicate());
    tbb.begin();
    for(int i=0; i<5; i++) {
      assertEquals((long)i, tbb.getLong());
    }
    tbb.rollback();
    for(int i=0; i<5; i++) {
      assertEquals((long)i, tbb.getLong());
    }
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.getLong(); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    for(int i=5; i<10; i++) {
      assertEquals((long)i, tbb.getLong());
    }
    for(int i=0; i<10; i++) {
      assertEquals((long)i, tbb.getLong());
    }
    assertEquals(0, tbb.remaining());
  }
  
  @Test
  public void getPopTest() {
    String s = "TEST1234567890";
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    int size = tbb.remaining();
    tbb.begin();
    tbb.pop();
    tbb.rollback();
    assertEquals(size, tbb.remaining());
    ByteBuffer bb = tbb.pop();
    assertEquals(s, new String(bb.array()));
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.pop(); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    bb = tbb.pop();
    assertEquals(s, new String(bb.array()));
    bb = tbb.pop();
    assertEquals(s, new String(bb.array()));
    bb = tbb.pop();
    assertEquals(s, new String(bb.array()));
  }
  
  @Test
  public void getPullTest() {
    String s = "TEST1234567890";
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    int size = tbb.remaining();
    tbb.begin();
    tbb.pull(16);
    tbb.pull(8);
    tbb.rollback();
    assertEquals(size, tbb.remaining());
    ByteBuffer bb = tbb.pull(16);
    assertEquals(s+"TE", new String(bb.array()));
    
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.pull(4); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    
    bb = tbb.pull(tbb.remaining());
    assertEquals("ST1234567890"+s+s, new String(bb.array()));
  }
  
  @Test
  public void getDiscardTest() {
    String s = "TEST1234567890";
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    int size = tbb.remaining();
    tbb.begin();
    tbb.discard(16);
    tbb.discard(8);
    tbb.rollback();
    assertEquals(size, tbb.remaining());
    tbb.discard(4);
    ByteBuffer bb = tbb.pull(10);
    System.out.println(bb);
    assertEquals("1234567890", new String(bb.array(), 4, 10));
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.discard(4); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    
    tbb.discard(tbb.remaining());
    assertEquals(0, tbb.remaining());
  }
  
  @Test
  public void getAsStringTest() {
    String s = "TEST1234567890";
    final TransactionalByteBuffers tbb = new TransactionalByteBuffers();
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    tbb.add(ByteBuffer.wrap(s.getBytes()));
    int size = tbb.remaining();
    tbb.begin();
    tbb.getAsString(16);
    tbb.getAsString(8);
    tbb.rollback();
    assertEquals(size, tbb.remaining());
    assertEquals(s+"TE", tbb.getAsString(16));
    
    tbb.begin();
    final AtomicBoolean hitException = new AtomicBoolean(false);
    PS.execute(new Runnable() {
      @Override
      public void run() {
        try {
          tbb.getAsString(4); 
        } catch(Exception e) {
          hitException.set(true);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        return  hitException.get();
      }
    }.blockTillTrue(5000);
    tbb.commit();
    
    assertEquals("ST1234567890"+s+s, tbb.getAsString(tbb.remaining()));
  }
}
