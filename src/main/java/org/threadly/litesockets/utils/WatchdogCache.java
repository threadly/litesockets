package org.threadly.litesockets.utils;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.SimpleSchedulerInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.Watchdog;


/**
 * This is used to help simplify scheduling timeout tasks.  Its more lightweight then
 * scheduling each task individually, especially when more the one task has the same timeout.
 *
 */
public class WatchdogCache {
  private final ConcurrentHashMap<Long, Watchdog> dogs = new ConcurrentHashMap<Long, Watchdog>();
  private final SimpleSchedulerInterface scheduler;

  public WatchdogCache(SimpleSchedulerInterface ssi) {
    scheduler = ssi;
  }

  /**
   * Call this to schedule a timeout on a {@link ListenableFuture}.  This works almost exactly
   * like {@link Watchdog#watch(ListenableFuture)} except you can specify the delay.
   * 
   * @param lf the {@link ListenableFuture} to monitor.
   * @param delay the amount of time to give the {@link ListenableFuture} till its cancelled.
   */
  public void watch(ListenableFuture<?> lf, long delay) {
    Watchdog wd = dogs.get(delay);
    if(wd == null) {
      Watchdog wd2 = new Watchdog(scheduler, delay, false);
      wd = dogs.putIfAbsent(delay, wd2);
      if(wd == null) {
        wd = wd2;
      }
    }
    wd.watch(lf);
  }

  /**
   * The WatchdogCache uses {@link Watchdog} objects to track Futures.  If you are
   * creating many of these they might need to be cleaned up.  Calling this will
   * clean up any {@link Watchdog} object not currently in use.
   */
  public void cleanup() {
    Iterator<Watchdog> iter = dogs.values().iterator();
    while(iter.hasNext()) {
      Watchdog lwd = iter.next();
      if(!lwd.isActive()) {
        iter.remove();
      }
    }
  }
  
  /**
   * Calling this will clean up all {@link Watchdog} objects, though if they are
   * currently monitoring a {@link ListenableFuture} they will still be in memory till
   * all {@link ListenableFuture} are done being monitored.
   */
  public void cleanAll() {
    dogs.clear();
  }

  /**
   * @return the number of unique {@link Watchdog} objects currently in the Cache.
   */
  public int size() {
    return dogs.size();
  }
}
