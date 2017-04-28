package org.threadly.litesockets.tcp;

import java.io.IOException;
import java.net.ConnectException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.litesockets.HashedSocketExecuter;
import org.threadly.litesockets.NoThreadSocketExecuter;
import org.threadly.litesockets.utils.PortUtils;

public class HashedSocketExecuterTCPTests extends TCPTests {
  
  @Before
  public void start() throws IOException {
    port = PortUtils.findTCPPort();
    PS = new PriorityScheduler(5);
    SE = new HashedSocketExecuter(PS);
    SE.start();
    serverFC = new FakeTCPServerClient();
    server = SE.createTCPServer("localhost", port);
    server.setClientAcceptor(serverFC);
    server.addCloseListener(serverFC);
    server.start();
  }
  
  @Override
  @After
  public void stop() throws Exception{
    System.out.println("Test Stopped");
    super.stop();
    System.gc();
    System.gc();
    System.gc();
    System.out.println("Used Memory:"
        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024));
  }
  
  @Test
  public void simpleWriteTest() throws IOException, InterruptedException {
    super.simpleWriteTest();
  }
  
  @Test
  public void clientBlockingWriter() throws Exception {
    super.clientBlockingWriter();
  }
  
  @Test(expected=ConnectException.class)
  public void tcpConnectionRefused() throws Throwable {
    super.tcpConnectionRefused();
  }
}
