package org.threadly.litesockets;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.After;
import org.junit.Before;
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
  }

}
