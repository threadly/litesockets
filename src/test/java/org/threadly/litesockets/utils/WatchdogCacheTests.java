package org.threadly.litesockets.utils;

import static org.junit.Assert.*;

import java.util.LinkedList;
import java.util.concurrent.ExecutionException;

import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.test.concurrent.TestableScheduler;



public class WatchdogCacheTests {

  @Test
  public void cleanupTest() {
    int test_amount = 60;
    TestableScheduler TS = new TestableScheduler();
    WatchdogCache wdc = new WatchdogCache(TS);
    LinkedList<SettableListenableFuture<?>> slfl = new LinkedList<SettableListenableFuture<?>>();
    for(int i=0; i<test_amount; i++) {
      SettableListenableFuture<?> slf = new SettableListenableFuture<Boolean>(true);
      slfl.add(slf);
      wdc.watch(slf, 1+i);
    }
    for(int i=0; i<test_amount; i++) {
      SettableListenableFuture<?> slf = new SettableListenableFuture<Boolean>(true);
      slfl.add(slf);
      wdc.watch(slf, 1+i);
    }
    assertEquals(test_amount, wdc.size());
    for(int i=0; i<test_amount*100; i++) {
      TS.advance(1);
    }
    wdc.cleanup();
    assertEquals(0, wdc.size());
    for(SettableListenableFuture<?> slf: slfl) {
      assertTrue(slf.isDone());
    }
  }

  @Test
  public void multiThreadTest() throws InterruptedException, ExecutionException {
    final int test_amount = 60;
    PriorityScheduler PS = new PriorityScheduler(5);
    TestableScheduler TS = new TestableScheduler();
    final WatchdogCache wdc = new WatchdogCache(TS);
    Runnable runner = new Runnable() {
      public void run() {
        for(int i=0; i<test_amount; i++) {
          SettableListenableFuture<?> slf = new SettableListenableFuture<Boolean>(true);
          wdc.watch(slf, 1+i);
        }
      }
    };
    PS.submit(runner);
    PS.submit(runner);
    PS.submit(runner);
    PS.submit(runner);
    PS.submit(runner).get();
    SettableListenableFuture<?> slf = new SettableListenableFuture<Boolean>(true);
    wdc.watch(slf, (test_amount*100)+1000);
    assertEquals(test_amount+1, wdc.size());
    for(int i=0; i<(test_amount*100); i++) {
      TS.advance(1);
    }
    wdc.cleanup();
    assertEquals(1, wdc.size());
    for(int i=0; i<(test_amount*100); i++) {
      TS.advance(1);
    }
    wdc.cleanAll();
    assertEquals(0, wdc.size());

  }

}
