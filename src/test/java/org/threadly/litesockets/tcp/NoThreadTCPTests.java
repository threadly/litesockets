package org.threadly.litesockets.tcp;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.utils.PortUtils;

public class NoThreadTCPTests extends TCPTests {
  NoThreadSocketExecuter ntSE;
  SingleThreadScheduler STS;
  volatile boolean keepRunning = true;
  
  @Before
  public void start() throws IOException {
    keepRunning = true;
    port = PortUtils.findTCPPort();
    STS = new SingleThreadScheduler();
    PS = new PriorityScheduler(5);
    ntSE = new NoThreadSocketExecuter();
    SE = ntSE;
    SE.start();
    STS.execute(new Runnable() {
      @Override
      public void run() {
        while(ntSE.isRunning() && keepRunning) {
          ntSE.select(100);
        }
      }});
    serverFC = new FakeTCPServerClient();
    server = SE.createTCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
  }
  
  @Override
  @After
  public void stop() throws Exception{
    super.stop();
    STS.shutdownNow();
    keepRunning = false;
    System.gc();
    System.gc();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test
  public void clientBlockingWriter() throws Exception {
   super.clientBlockingWriter(); 
  }
}
