package org.threadly.litesockets;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.SchedulerServiceInterface;
import org.threadly.concurrent.SimpleSchedulerInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.WatchdogCache;
import org.threadly.litesockets.SEUtils.SocketExecuterByteStats;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.AbstractService;
import org.threadly.util.ArgumentVerifier;

abstract class SocketExecuterCommonBase extends AbstractService implements SocketExecuterInterface {
  public static final int WATCHDOG_CLEANUP_TIME = 30000;
  protected final SchedulerServiceInterface acceptScheduler;
  protected final SchedulerServiceInterface readScheduler;
  protected final SchedulerServiceInterface writeScheduler;
  protected final SimpleSchedulerInterface schedulerPool;
  protected final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  protected final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();
  protected final SocketExecuterByteStats stats = new SocketExecuterByteStats();
  protected final WatchdogCache dogCache;
  protected Selector readSelector;
  protected Selector writeSelector;
  protected Selector acceptSelector;
  
  SocketExecuterCommonBase(SchedulerServiceInterface scheduler) {
    this(scheduler,scheduler,scheduler,scheduler);
  }
  
  SocketExecuterCommonBase(SchedulerServiceInterface acceptScheduler, 
      SchedulerServiceInterface readScheduler, 
      SchedulerServiceInterface writeScheduler, 
      SimpleSchedulerInterface ssi) {
    ArgumentVerifier.assertNotNull(ssi, "ThreadScheduler");    
    ArgumentVerifier.assertNotNull(acceptScheduler, "Accept Scheduler");
    ArgumentVerifier.assertNotNull(readScheduler, "Read Scheduler");
    ArgumentVerifier.assertNotNull(writeScheduler, "Write Scheduler");
    schedulerPool = ssi;
    dogCache = new WatchdogCache(ssi, true);
    this.acceptScheduler = acceptScheduler;
    this.readScheduler = readScheduler;
    this.writeScheduler = writeScheduler;
  }

  protected abstract void addThreadExecutorToClient(Client client);
  
  @Override
  public void addClient(final Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(! client.isClosed() && client.getProtocol() == WireProtocol.TCP && isRunning()) {
      addThreadExecutorToClient(client);
      client.setClientsSocketExecuter(this);
      if(client.getChannel() != null && client.getChannel().isConnected()) {
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          flagNewWrite(client);
          flagNewRead(client);
        }
      } else {
        client.connect();
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc== null) {
          readScheduler.execute(new SEUtils.AddToSelector(client, readSelector, SelectionKey.OP_CONNECT));
          readSelector.wakeup();
          dogCache.watch(client.connect(), client.getTimeout());
        }
      }
    }
  }
  
  @Override
  public void removeClient(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning() && clients.remove(client.getChannel()) != null) {
      readScheduler.execute(new SEUtils.RemoveFromSelector(client.getChannel(), readSelector));
      writeScheduler.execute(new SEUtils.RemoveFromSelector(client.getChannel(), writeSelector));
      readSelector.wakeup();
      writeSelector.wakeup();
    }
  }
  
  @Override
  public void addServer(final Server server) {
    ArgumentVerifier.assertNotNull(server, "Server");
    if(isRunning()) {
      Server sn = servers.putIfAbsent(server.getSelectableChannel(), server);
      if(sn == null) {
        server.setSocketExecuter(this);
        server.setThreadExecutor(schedulerPool);
        if(server.getServerType() == WireProtocol.TCP) {
          acceptScheduler.execute(new SEUtils.AddToSelector(server, acceptSelector, SelectionKey.OP_ACCEPT));
        } else if(server.getServerType() == WireProtocol.UDP) {
          acceptScheduler.execute(new SEUtils.AddToSelector(server, acceptSelector, SelectionKey.OP_READ));
        }
        acceptSelector.wakeup();
      }
    }
  }
  
  @Override
  public void removeServer(Server server) {
    ArgumentVerifier.assertNotNull(server, "Server");
    if(isRunning() && servers.remove(server.getSelectableChannel()) != null) {
      acceptScheduler.execute(new SEUtils.RemoveFromSelector(server.getSelectableChannel(), acceptSelector));
      acceptSelector.wakeup();
    }
  }
  
  @Override
  public int getClientCount() {
    return clients.size();
  }

  @Override
  public int getServerCount() {
    return servers.size();
  }
  
  @Override
  public SimpleSchedulerInterface getThreadScheduler() {
    return schedulerPool;
  }
  
  @Override
  public SimpleByteStats getStats() {
    return stats;
  }
  
  @Override
  public void watchFuture(ListenableFuture<?> lf, long delay) {
    dogCache.watch(lf, delay);
  }
}
