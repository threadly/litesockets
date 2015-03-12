package org.threadly.litesockets.tcp;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.ThreadedSocketExecuter;

public class NoThreadTCPTests extends TCPTests {
  NoThreadSocketExecuter ntSE;
  SingleThreadScheduler STS;
  
  @Before
  public void start() throws IOException {
    STS = new SingleThreadScheduler();
    PS = new PriorityScheduler(5, 5, 100000);
    ntSE = new NoThreadSocketExecuter();
    SE = ntSE;
    SE.start();
    STS.execute(new Runnable() {
      @Override
      public void run() {
        while(ntSE.isRunning()) {
          ntSE.select(1000);
        }
      }});
    serverFC = new FakeTCPServerClient(SE);
    server = new TCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);
  }
  
  @Override
  @After
  public void stop() {
    super.stop();
    STS.shutdownNow();
  }

}
