package org.threadly.litesockets.utils;

import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.SimpleSchedulerInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.Watchdog;

public class WatchdogCache {

  private final ConcurrentHashMap<Long, Watchdog> dogs = new ConcurrentHashMap<Long, Watchdog>();
  private final SimpleSchedulerInterface scheduler;
  
  public WatchdogCache(SimpleSchedulerInterface ssi) {
    scheduler = ssi;
  }
  
  public void watch(ListenableFuture<?> lf, long delay) {
    Watchdog wd = dogs.get(delay);
    if(wd == null) {
      wd = dogs.putIfAbsent(delay, new Watchdog(scheduler, delay, false));
      wd = dogs.get(delay);
    }
    wd.watch(lf);
  }
  
}
