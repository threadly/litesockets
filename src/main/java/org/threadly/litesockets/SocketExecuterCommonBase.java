package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.WatchdogCache;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.AbstractService;
import org.threadly.util.ArgumentVerifier;

/**
 *  This is a common base class for the Threaded and NoThread SocketExecuters. 
 */
abstract class SocketExecuterCommonBase extends AbstractService implements SocketExecuter {
  protected final Logger log = Logger.getLogger(this.getClass().toString());
  protected final SubmitterScheduler schedulerPool;
  protected final SubmitterScheduler acceptScheduler;
  protected final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<>();
  protected final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<>();
  protected final SocketExecuterByteStats stats = new SocketExecuterByteStats();
  protected final WatchdogCache dogCache;
  protected volatile boolean perConnectionStatsEnabled = true;
  protected Selector acceptSelector;

  SocketExecuterCommonBase(final SubmitterScheduler scheduler) {
    this(scheduler, scheduler);
  }

  SocketExecuterCommonBase(final SubmitterScheduler acceptScheduler, final SubmitterScheduler ssi) {
    log.setParent(Logger.getGlobal());
    ArgumentVerifier.assertNotNull(ssi, "ThreadScheduler");    
    ArgumentVerifier.assertNotNull(acceptScheduler, "Accept Scheduler");
    
    schedulerPool = ssi;
    dogCache = new WatchdogCache(ssi, true);
    this.acceptScheduler = acceptScheduler;
  }

  @Override
  public long getTotalPendingWriteBytes() {
    long result = 0;
    for (Client c : clients.values()) {
      result += c.getWriteBufferSize();
    }
    return result;
  }
  
  @Override
  public long getTotalPendingReadBytes() {
    long result = 0;
    for (Client c : clients.values()) {
      result += c.getReadBufferSize();
    }
    return result;
  }

  @Override
  public void setPerConnectionStatsEnabled(boolean enabled) {
    perConnectionStatsEnabled = enabled;
  }

  protected void recordReadStats(int size) {
    stats.addRead(size);
  }

  protected void recordWriteStats(int size) {
    stats.addWrite(size);
  }

  protected void checkRunning() {
    if(!isRunning()) {
      throw new IllegalStateException("SocketExecuter is not running!");
    }
  }

  @Override
  public TCPClient createTCPClient(final String host, final int port) throws IOException {
    checkRunning();
    TCPClient tc = new TCPClient(this, host, port, perConnectionStatsEnabled);
    clients.put(((Client)tc).getChannel(), tc);
    return tc;
  }


  @Override
  public TCPClient createTCPClient(final SocketChannel sc) throws IOException {
    checkRunning();
    final TCPClient tc = new TCPClient(this, sc, perConnectionStatsEnabled);
    clients.put(((Client)tc).getChannel(), tc);
    this.setClientOperations(tc);
    return tc;
  }

  @Override
  public TCPServer createTCPServer(final String host, final int port) throws IOException {
    checkRunning();
    TCPServer ts = new TCPServer(this, host, port);
    servers.put(ts.getSelectableChannel(), ts);
    return ts;
  }

  @Override
  public TCPServer createTCPServer(final ServerSocketChannel ssc) throws IOException {
    checkRunning();
    TCPServer ts = new TCPServer(this, ssc);
    servers.put(ts.getSelectableChannel(), ts);
    return ts;
  }

  @Override
  public UDPServer createUDPServer(final String host, final int port) throws IOException {
    checkRunning();
    UDPServer us = new UDPServer(this, host, port);
    servers.put(us.getSelectableChannel(), us);
    return us;
  }

  protected boolean checkServer(final Server server) {
    if(!isRunning() || server.isClosed() || server.getSocketExecuter() != this || 
       !servers.containsKey(server.getSelectableChannel())) {
      servers.remove(server.getSelectableChannel());
      return false;
    }
    return true;
  }


  @Override
  public void startListening(final Server server) {
    if(!checkServer(server)) {
      return;
    } else {
      if(server.getServerType() == WireProtocol.TCP) {
        acceptScheduler.execute(()->executeServerOperations(acceptScheduler, server, acceptSelector, SelectionKey.OP_ACCEPT));
        acceptSelector.wakeup();
      } else {
        throw new UnsupportedOperationException("Unknown Server WireProtocol!"+ server.getServerType());
      }
    }
  }

  @Override
  public void stopListening(final Server server) {
    if(!checkServer(server)) {
      return;
    } else {
      if(server instanceof TCPServer) {
        acceptScheduler.execute(()->executeServerOperations(acceptScheduler, server, acceptSelector, 0));
        acceptSelector.wakeup();
      } else if(server instanceof UDPServer) {
        this.setUDPServerOperations((UDPServer)server, false);
      } else {
        throw new UnsupportedOperationException("Unknown Server type!"+ server.getServerType());
      }
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
  public SubmitterScheduler getThreadScheduler() {
    return schedulerPool;
  }

  @Override
  public SimpleByteStats getStats() {
    return stats;
  }

  @Override
  public void watchFuture(final ListenableFuture<?> lf, final long delay) {
    dogCache.watch(lf, delay);
  }

  protected static Selector openSelector() {
    try {
      return Selector.open();
    } catch (IOException e) {
      throw new StartupException(e);
    }
  }

  protected void closeSelector(final SubmitterScheduler scheduler, final Selector selector) {
    scheduler.execute(new Runnable() {
      @Override
      public void run() {
        IOUtils.closeQuietly(selector);
      }});
    selector.wakeup();
  }

  protected void doServerAccept(final Server server) {
    if(server != null) {
      try {
        SocketChannel client = ((ServerSocketChannel)server.getSelectableChannel()).accept();
        while(client != null) {
          client.configureBlocking(false);
          server.acceptChannel(client);
          client = ((ServerSocketChannel)server.getSelectableChannel()).accept();
        }
      } catch (IOException e) {
        server.close(e);
      }
    }
  }

  protected void doClientConnect(final Client client, final Selector selector) {
    if(client == null) {
      return;
    }
    client.getClientsThreadExecutor().execute(()-> {
      try {
        if(client.getChannel().finishConnect()) {
          client.setConnectionStatus(null);
        }
        setClientOperations(client);
      } catch(IOException e) {
        client.close(e);
        client.setConnectionStatus(e);
      }
    });
  }

  protected void doClientWrite(final Client client, final Selector selector) {
    if(client != null) {
      try {
        final SelectionKey sk = client.getChannel().keyFor(selector);

        sk.interestOps(sk.interestOps()&~SelectionKey.OP_WRITE);
        client.doSocketWrite(false);
      } catch (Throwable t) {
        client.close(t);
      }
    }
  }

  protected void doClientRead(final Client client, final Selector selector) {
    if(client != null) {
      final SelectionKey sk = client.getChannel().keyFor(selector);
      try {
        sk.interestOps(sk.interestOps() & ~SelectionKey.OP_READ);
        client.doSocketRead(false);
      } catch (Throwable t) {
        client.close(t);
      }
    }
  }

  protected static void executeServerOperations(final Executor exec, final Server server, 
                                                final Selector selector, final int registerType) {
    if(!server.isClosed()  && selector.isOpen()) {
      try {
        server.getSelectableChannel().register(selector, registerType);
      } catch (ClosedChannelException e) {
        server.close(e);
      }
    }
  }

  /**
   * Implementation of the SimpleByteStats.
   */
  protected static class SocketExecuterByteStats extends SimpleByteStats {
    @Override
    protected void addWrite(final int size) {
      super.addWrite(size);
    }

    @Override
    protected void addRead(final int size) {
      super.addRead(size);
    }
  }
}
