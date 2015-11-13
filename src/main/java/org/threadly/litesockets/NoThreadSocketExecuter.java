package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.Executor;

import org.threadly.concurrent.NoThreadScheduler;
import org.threadly.util.ArgumentVerifier;

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
  private final NoThreadScheduler localNoThreadScheduler;
  private Selector commonSelector;

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
      commonSelector.wakeup();
    }
  }

  @Override
  public void setClientOperations(final Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(!clients.containsKey(client.getChannel()) || client.isClosed()) {
      clients.remove(client.getChannel());
      return;
    }
    if(client.getChannel().isConnectionPending()) {
      schedulerPool.execute(new AddToSelector(client, commonSelector, SelectionKey.OP_CONNECT));
    } else if(client.canWrite() && client.canRead()) {
      schedulerPool.execute(new AddToSelector(client, commonSelector, SelectionKey.OP_WRITE|SelectionKey.OP_READ));
    } else if (client.canRead()){
      schedulerPool.execute(new AddToSelector(client, commonSelector, SelectionKey.OP_READ));
    } else if (client.canWrite()){
      schedulerPool.execute(new AddToSelector(client, commonSelector, SelectionKey.OP_WRITE));
    } else {
      schedulerPool.execute(new AddToSelector(client, commonSelector, 0));
    }
    commonSelector.wakeup();
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
    localNoThreadScheduler.clearTasks();
    if(commonSelector != null && commonSelector.isOpen()) {
      commonSelector.wakeup();
      closeSelector(schedulerPool, commonSelector);
      commonSelector.wakeup();
    }
    clients.clear();
    servers.clear();
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
    localNoThreadScheduler.tick(null);
    try {
      if(delay == 0) {
        commonSelector.selectNow();
      } else {
        commonSelector.select(delay);
      }
      for(final SelectionKey key: commonSelector.selectedKeys()) {
        try {
          if(key.isAcceptable()) {
            doServerAccept(servers.get(key.channel()));
          } else {
            final Client tmpClient = clients.get(key.channel());
            if(key.isConnectable() && tmpClient != null) {
              doClientConnect(tmpClient, commonSelector);
              key.cancel(); //Stupid windows bug here.
              setClientOperations(tmpClient);
            } else if(key.isReadable()) {
              stats.addRead(doClientRead(tmpClient, commonSelector));
              final Server server = servers.get(key.channel());
              if(server != null && server.getServerType() == WireProtocol.UDP) {
                server.acceptChannel((DatagramChannel)server.getSelectableChannel());
              }
            } else if(key.isWritable()) {
              stats.addWrite(doClientWrite(tmpClient, commonSelector));
            }
          }
        } catch(CancelledKeyException e) {
          //Key could be cancelled at any point, we dont really care about it.
        }
      }
      //Also for windows bug, canceled keys are not removed till we select again.
      //So we just have to at the end of the loop.
      commonSelector.selectNow(); 
    } catch (IOException e) {
      //There is really nothing to do here but try again, usually this is because of shutdown.
    } catch(ClosedSelectorException e) {
      //We do nothing here because the next loop should not happen now.
    } catch (NullPointerException e) {
      //There is a bug in some JVMs around this where the select() can throw an NPE from native code.
    }
    localNoThreadScheduler.tick(null);
  }

  @Override
  public Executor getExecutorFor(final Client obj) {
    return localNoThreadScheduler;
  }
}
