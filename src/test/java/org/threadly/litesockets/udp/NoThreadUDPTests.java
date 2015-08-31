package org.threadly.litesockets.udp;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.NoThreadSocketExecuter;

public class NoThreadUDPTests extends UDPTest {
  NoThreadSocketExecuter ntSE;

  @Before
  public void start() throws IOException {
    PS = new PriorityScheduler(5);
    ntSE = new NoThreadSocketExecuter();
    SE = ntSE;
    SE.start();
    PS.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        ntSE.select();
      }}, 10, 10);
    serverFC = new FakeUDPServerClient(SE);
    server = SE.createUDPServer("127.0.0.1", port);
    server.setClientAcceptor(serverFC);
    server.start();
  }
  
  @After
  public void stop() {
    server.close();
    SE.stop();
    PS.shutdownNow();
  }

}
