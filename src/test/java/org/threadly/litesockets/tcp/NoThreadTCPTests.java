package org.threadly.litesockets.tcp;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.litesockets.NoThreadSocketExecuter;

public class NoThreadTCPTests extends TCPTests {
  NoThreadSocketExecuter ntSE;
  SingleThreadScheduler STS;
  volatile boolean keepRunning = true;
  
  @Before
  public void start() throws IOException {
    keepRunning = true;
    port = Utils.findTCPPort();
    STS = new SingleThreadScheduler();
    PS = new PriorityScheduler(5);
    ntSE = new NoThreadSocketExecuter();
    SE = ntSE;
    SE.start();
    STS.execute(new Runnable() {
      @Override
      public void run() {
        while(ntSE.isRunning() && keepRunning) {
          ntSE.select(1000);
        }
      }});
    serverFC = new FakeTCPServerClient(SE);
    server = SE.createTCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
  }
  
  @Override
  @After
  public void stop(){
    keepRunning = false;
    if(SE.isRunning()) {    
      ntSE.wakeup();
      ntSE.wakeup();
      ntSE.wakeup();
      ntSE.wakeup();
    }
    super.stop();
    STS.shutdownNow();
  }
}
