package org.threadly.litesockets;

import org.threadly.concurrent.SingleThreadScheduler;

public class SingleThreadSocketExecuter extends NoThreadSocketExecuter {
  
  private final SingleThreadScheduler sts = new SingleThreadScheduler();
  
  public SingleThreadSocketExecuter() {
    super();
  }
  
  @Override
  protected void startupService() {
    super.startupService();
    sts.execute(()->{
      while(isRunning()) {
        super.select(10000);
      }
    });
  }
  
  public void select() {
  }

  public void select(int delay) {
  }
}
