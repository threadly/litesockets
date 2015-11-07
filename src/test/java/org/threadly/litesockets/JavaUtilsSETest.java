package org.threadly.litesockets;

import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.tcp.Utils;

public class JavaUtilsSETest extends ServerExecuterTests{
  ScheduledThreadPoolExecutor sch;
  
  @Before
  public void start() {
    sch = new ScheduledThreadPoolExecutor(10);
    port = Utils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(sch);
    SE.start();
  }
  
  @After
  public void stop() {
    SE.stopIfRunning();
    PS.shutdownNow();
    sch.shutdownNow();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  
  //@Test
  public void loop() throws IOException, InterruptedException {
    for(int i=0; i<1000; i++) {
      SEStatsTest();
      stop();
      start();
    }
  }

}
