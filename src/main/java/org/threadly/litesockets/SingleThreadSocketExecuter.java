package org.threadly.litesockets;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.NoThreadScheduler;
import org.threadly.util.ExceptionUtils;

/**
 * This is a SingleThreaded implementation of a SocketExecuter.  If single threaded performance is 
 * desired it should be slightly less overhead than a {@link ThreadedSocketExecuter}.
 */
public class SingleThreadSocketExecuter extends NoThreadSocketExecuter {
  private static final int SELECT_TIME_MS = 10000;
  private static final ConfigurableThreadFactory THREAD_FACTORY = 
      new ConfigurableThreadFactory("SingleThreadSocketExecuter-", false, true, 
                                    Thread.currentThread().getPriority(), null, null);
  
  private Thread runningThread = null;

  /**
   * Constructs a SingleThreadSocketExecuter.  {@link #start()} must still be called before using it.
   */
  public SingleThreadSocketExecuter() {
    super();
  }

  /**
   * Constructs a SingleThreadSocketExecuter.  {@link #start()} must still be called before using it.
   * <p>
   * This accepts a {@link NoThreadScheduler} so that stats can be collected if desired.
   */
  public SingleThreadSocketExecuter(NoThreadScheduler scheduler) {
    super(scheduler);
  }
  
  @Override
  protected void startupService() {
    super.startupService();
    runningThread = THREAD_FACTORY.newThread(()->{
      while(isRunning()) {
        try {
          super.select(SELECT_TIME_MS);
        } catch (Throwable t) {
          ExceptionUtils.handleException(t);
        }
      }
    });
    runningThread.start();
  }
  
  @Override
  protected void shutdownService() {
    super.shutdownService();
    
    try {
      runningThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @Override
  public void select() {
  }

  @Override
  public void select(int delay) {
  }
}
