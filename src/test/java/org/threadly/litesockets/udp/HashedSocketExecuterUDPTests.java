package org.threadly.litesockets.udp;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.HashedSocketExecuter;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.tcp.FakeTCPServerClient;
import org.threadly.litesockets.utils.PortUtils;

public class HashedSocketExecuterUDPTests extends UDPTest {
  NoThreadSocketExecuter ntSE;

  @Before
  public void start() throws IOException {
    port = PortUtils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new HashedSocketExecuter(PS);
    SE.start();
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
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  
  public void manyUDPConnects() throws IOException, InterruptedException {
    super.manyUDPConnects();
  }
}
