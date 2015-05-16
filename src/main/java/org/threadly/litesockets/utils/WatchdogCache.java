package org.threadly.litesockets.utils;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.SimpleSchedulerInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.Watchdog;
import org.threadly.util.AbstractService;
import org.threadly.util.Clock;

public class WatchdogCache extends AbstractService {
  public static final int CLEANUP_DELAY = 5000;
  private final ConcurrentHashMap<Long, LocalWatchdog> dogs = new ConcurrentHashMap<Long, LocalWatchdog>();
  private final SimpleSchedulerInterface scheduler;
  private final Runnable cleanupRunnable = new CleanupTask();
  
  public WatchdogCache(SimpleSchedulerInterface ssi) {
    scheduler = ssi;
  }
  
  public void watch(ListenableFuture<?> lf, long delay) {
    if(isRunning()) {
      Watchdog wd = dogs.get(delay);
      if(wd == null) {
        wd = dogs.putIfAbsent(delay, new LocalWatchdog(scheduler, delay, false));
        wd = dogs.get(delay);
      }
      wd.watch(lf);
    }
  }
  
  public void cleanup() {
    if(dogs.size() > 5) {
      Iterator<LocalWatchdog> iter = dogs.values().iterator();
      while(iter.hasNext()) {
        LocalWatchdog lwd = iter.next();
        if(lwd.isDone()) {
          iter.remove();
        }
      }
    }
  }

  @Override
  protected void startupService() {
    scheduler.schedule(cleanupRunnable, CLEANUP_DELAY);
  }

  @Override
  protected void shutdownService() {
    dogs.clear();
  }
  
  private class CleanupTask implements Runnable {
    @Override
    public void run() {
      cleanup();
      if(isRunning()) {
        scheduler.schedule(cleanupRunnable, CLEANUP_DELAY);
      }
    }
  }
  
  private static class LocalWatchdog extends Watchdog {
    private final long timeCheck;
    private volatile long lastAdded;

    public LocalWatchdog(SimpleSchedulerInterface scheduler,
        long timeoutInMillis, boolean sendInterruptToTrackedThreads) {
      super(scheduler, timeoutInMillis, sendInterruptToTrackedThreads);
      timeCheck = timeoutInMillis;
    }
    
    @Override
    public void watch(ListenableFuture<?> lf) {
      lastAdded = Clock.lastKnownForwardProgressingMillis();
      super.watch(lf);
    }
    
    public boolean isDone() {
      return Clock.lastKnownForwardProgressingMillis() - lastAdded > timeCheck;
    }
    
  }
}
