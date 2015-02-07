package org.threadly.litesockets.tcp;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.ThreadedSocketExecuter;

public class NoThreadTCPTests extends TCPTests {
  NoThreadSocketExecuter ntSE;
  
  @Before
  public void start() throws IOException {
    PS = new PriorityScheduler(5, 5, 100000);
    ntSE = new NoThreadSocketExecuter();
    SE = ntSE;
    SE.start();
    PS.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        ntSE.select(100);
      }}, 10, 10);
    serverFC = new FakeTCPServerClient(SE);
    server = new TCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.setCloser(serverFC);
    SE.addServer(server);

  }

}
