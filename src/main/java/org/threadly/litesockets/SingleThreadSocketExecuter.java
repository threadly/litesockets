package org.threadly.litesockets;

import org.threadly.concurrent.SingleThreadScheduler;

/**
 * This is a SingleThreaded implementation of a SocketExecuter. 
 * 
 * 
 *
 */
public class SingleThreadSocketExecuter extends NoThreadSocketExecuter {
  private static final int SELECT_TIME_MS = 10000;
  
  private final SingleThreadScheduler sts = new SingleThreadScheduler();
  
  public SingleThreadSocketExecuter() {
    super();
  }
  
  @Override
  protected void startupService() {
    super.startupService();
    sts.execute(()->{
      while(isRunning()) {
        super.select(SELECT_TIME_MS);
      }
    });
  }
  
  public void select() {
  }

  public void select(int delay) {
  }
}
