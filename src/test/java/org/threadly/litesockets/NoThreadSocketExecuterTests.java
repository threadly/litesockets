package org.threadly.litesockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.test.concurrent.TestCondition;

public class NoThreadSocketExecuterTests {

  @Test
  public void checkWakeUp() throws InterruptedException {
    final NoThreadSocketExecuter ntse = new NoThreadSocketExecuter();
    ntse.start();
    final AtomicInteger count = new AtomicInteger(0);
    SingleThreadScheduler sts = new SingleThreadScheduler();
    sts.execute(new Runnable() {
      @Override
      public void run() {
        while(count.incrementAndGet() < 100) {
          ntse.select(10000);
        }
      }});
    new TestCondition(){
      @Override
      public boolean get() {
        ntse.wakeup();
        return count.get()  == 100;
      }
    }.blockTillTrue(5000);
    assertEquals(100, count.get());
    sts.shutdownNow();
    assertTrue(sts.isShutdown());
    ntse.stop();
    assertFalse(ntse.isRunning());
  }
}
