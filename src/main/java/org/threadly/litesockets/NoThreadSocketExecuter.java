package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

import org.threadly.concurrent.NoThreadScheduler;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;

/**
 * <p>The NoThreadSocketExecuter is a simpler implementation of a {@link SocketExecuter} 
 * that does not create any threads. Since there are no threads operations happen on whatever thread
 * calls .select(), and only 1 thread at a time should ever call it at a time.  Other then
 * that it should be completely thread safe.</p>
 * 
 * <p>This is generally the implementation used by clients.  It can be used for servers
 * but only when not servicing many connections at once.  How many connections is hardware
 * and OS defendant.  For an average multi-core x86 linux server I a connections not to much more
 * then 1000 connections would be its limit, though alot depends on how active those connections are.</p>
 * 
 * <p>It should also be noted that all client read/close callbacks happen on the thread that calls select().</p>
 * 
 * @author lwahlmeier
 */
public class NoThreadSocketExecuter extends SocketExecuterCommonBase {
  public static final int SELECT_TIME_MS = 50;
  
  private final NoThreadScheduler localNoThreadScheduler;
  private Selector commonSelector;
  private volatile boolean wakeUp = false;

  /**
   * Constructs a NoThreadSocketExecuter.  {@link #start()} must still be called before using it.
   */
  public NoThreadSocketExecuter() {
    super(new NoThreadScheduler());
    localNoThreadScheduler = (NoThreadScheduler)schedulerPool; 
  }

  /**
   * This is used to wakeup the {@link Selector} assuming it was called with a timeout on it.
   * Most all methods in this class that need to do a wakeup do it automatically, but
   * there are situations where you might want to wake up the thread we are blocked on 
   * manually.
   */
  public void wakeup() {
    if(commonSelector != null && commonSelector.isOpen()) {
      wakeUp = true;
      commonSelector.wakeup();

    }
  }

  @Override
  public void setClientOperations(final Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(!clients.containsKey(client.getChannel())) {
      return;
    }
    schedulerPool.execute(()->this.doClientOperations(client));
  }

  @Override
  public void setUDPServerOperations(final UDPServer udpServer, final boolean enable) {
    if(checkServer(udpServer)) {
      if(enable) {
        if(udpServer.needsWrite()) {
          schedulerPool.execute(()->executeServerOperations(schedulerPool, udpServer, commonSelector, SelectionKey.OP_READ|SelectionKey.OP_WRITE));
        } else {
          schedulerPool.execute(()->executeServerOperations(schedulerPool, udpServer, commonSelector, SelectionKey.OP_READ));  
        }
      } else {
        schedulerPool.execute(()->executeServerOperations(schedulerPool, udpServer, commonSelector, 0));
      }
      commonSelector.wakeup();
    }
  }

  @Override
  protected void startupService() {
    commonSelector = openSelector();
    this.acceptSelector = commonSelector;
    this.readSelector = commonSelector;
    this.writeSelector = commonSelector;
  }

  @Override
  protected void shutdownService() {
    commonSelector.wakeup();
    for(final Client client: clients.values()) {
      IOUtils.closeQuietly(client);
    }
    for(final Server server: servers.values()) {
      IOUtils.closeQuietly(server);
    }
    if(commonSelector != null && commonSelector.isOpen()) {
      closeSelector(schedulerPool, commonSelector);
    }
    while(localNoThreadScheduler.hasTaskReadyToRun()) {
      try {
        localNoThreadScheduler.tick(null);
      } catch(Exception e) {

      }
    }
    clients.clear();
    servers.clear();
  }

  private void doClientOperations(final Client client) {
    SelectionKey sk = client.getChannel().keyFor(commonSelector);
    if(client.isClosed()) {
      clients.remove(client.getChannel());
      if(sk != null) {
        sk.cancel();
      }
      if(client.getChannel().isOpen()) {
        IOUtils.closeQuietly(client.getChannel());
      }
      return;
    }
    try {
      if(sk == null || !sk.isValid()) {
        sk = client.getChannel().register(commonSelector, 0);
      }
      if(client.getChannel().isConnectionPending()) {
        sk.interestOps(SelectionKey.OP_CONNECT);
      } else if(client.canWrite() && client.canRead()) {
        sk.interestOps(SelectionKey.OP_WRITE|SelectionKey.OP_READ);
      } else if (client.canRead()){
        sk.interestOps(SelectionKey.OP_READ);
      } else if (client.canWrite()){
        sk.interestOps(SelectionKey.OP_WRITE);
      } else {
        sk.interestOps(0);
      }
    } catch(Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * This will run all ExecuterTasks, check for pending network operations,
   * then run those operations.  There can be a lot of I/O operations so this
   * could take some time to run.  In general it should not be called from things like
   * GUI threads.
   * 
   */
  public void select() {
    select(0);
  }

  /**
   * This is the same as the {@link #select()} but it allows you to set a delay.
   * This delay is the time to wait for socket operations to happen.  It will
   * block the calling thread for up to this amount of time, but it could be less
   * if any network operation happens (including another thread adding a client/server). 
   * 
   * 
   * @param delay Max time in milliseconds to block for.
   */
  public void select(final int delay) {
    ArgumentVerifier.assertNotNegative(delay, "delay");
    checkRunning();
    long startTime = 0;
    boolean runOnce = false;
    if(delay == 0) {
      runOnce = true;
    } else {
      startTime = Clock.accurateForwardProgressingMillis();
    }

    while(isRunning() && !wakeUp && (runOnce || Clock.accurateForwardProgressingMillis() - startTime <= delay)) {
      try {
        commonSelector.selectNow();  //We have to do this before we tick for windows
        localNoThreadScheduler.tick(null);
        commonSelector.selectedKeys().clear();
        commonSelector.select(Math.min(delay, SELECT_TIME_MS));
        if(isRunning()) {
          for(final SelectionKey key: commonSelector.selectedKeys()) {
            try {
              if(key.isAcceptable()) {
                doServerAccept(servers.get(key.channel()));
              } else {
                final Client tmpClient = clients.get(key.channel());
                if(tmpClient != null) {
                  if(key.isConnectable()) {
                    try {
                      if(tmpClient.getChannel().finishConnect()) {
                        tmpClient.setConnectionStatus(null);
                      }
                    } catch(IOException e) {
                      IOUtils.closeQuietly(tmpClient);
                      tmpClient.setConnectionStatus(e);
                      ExceptionUtils.handleException(e);
                    }
                  } else {
                    if (key.isReadable()) {
                      tmpClient.doSocketRead(true);
                    } 
                    if(key.isWritable()) {
                      tmpClient.doSocketWrite(true);
                    }
                  }
                  doClientOperations(tmpClient);
                } else {
                  final Server server = servers.get(key.channel());
                  if(key.isReadable()) {
                    if(server != null && server.getServerType() == WireProtocol.UDP) {
                      server.acceptChannel((DatagramChannel)server.getSelectableChannel());
                    }
                  }
                  if(key.isWritable()) {
                    if(server != null) {
                      if(server instanceof UDPServer) {
                        UDPServer us = (UDPServer) server;
                        stats.addWrite(us.doWrite());
                        setUDPServerOperations(us, true);
                      }
                    }
                  }
                }
              }
            } catch(CancelledKeyException e) {
              //Key could be cancelled at any point, we dont really care about it.
            }
          }
          //Also for windows bug, canceled keys are not removed till we select again.
          //So we just have to at the end of the loop.
          commonSelector.selectNow();
          localNoThreadScheduler.tick(null);
        }
      } catch (IOException e) {
        //There is really nothing to do here but try again, usually this is because of shutdown.
      } catch(ClosedSelectorException e) {
        //We do nothing here because the next loop should not happen now.
      } catch (NullPointerException e) {
        //There is a bug in some JVMs around this where the select() can throw an NPE from native code.
      }
      if(runOnce) {
        break;
      }
    }
    wakeUp = false;
  }

  @Override
  public SubmitterExecutor getExecutorFor(final Object obj) {
    return localNoThreadScheduler;
  }
}
