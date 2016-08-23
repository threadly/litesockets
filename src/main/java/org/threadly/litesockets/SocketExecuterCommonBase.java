package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
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
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.AbstractService;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.ExceptionUtils;


/**
 *  This is a common base class for the Threaded and NoThread SocketExecuters. 
 */
abstract class SocketExecuterCommonBase extends AbstractService implements SocketExecuter {
  
  private final Logger log = Logger.getLogger(this.getClass().toString());
  protected final SubmitterScheduler acceptScheduler;
  protected final SubmitterScheduler readScheduler;
  protected final SubmitterScheduler writeScheduler;
  protected final SubmitterScheduler schedulerPool;
  protected final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  protected final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();
  protected final SocketExecuterByteStats stats = new SocketExecuterByteStats();
  protected final WatchdogCache dogCache;
  protected volatile boolean verboseLogging = false;
  protected Selector readSelector;
  protected Selector writeSelector;
  protected Selector acceptSelector;

  SocketExecuterCommonBase(final SubmitterScheduler scheduler) {
    this(scheduler,scheduler,scheduler,scheduler);
  }

  SocketExecuterCommonBase(final SubmitterScheduler acceptScheduler, 
      final SubmitterScheduler readScheduler, 
      final SubmitterScheduler writeScheduler, 
      final SubmitterScheduler ssi) {
    log.setParent(Logger.getGlobal());
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

  protected void addReadAmount(int size) {
    stats.addRead(size);
  }

  protected void addWriteAmount(int size) {
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
    TCPClient tc = new TCPClient(this, host, port);
    clients.put(((Client)tc).getChannel(), tc);
    return tc;
  }


  @Override
  public TCPClient createTCPClient(final SocketChannel sc) throws IOException {
    checkRunning();
    final TCPClient tc = new TCPClient(this, sc);
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
    if(!isRunning() || server.isClosed() || server.getSocketExecuter() != this || !servers.containsKey(server.getSelectableChannel())) {
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
        acceptScheduler.execute(new AddToSelector(acceptScheduler, server, acceptSelector, SelectionKey.OP_ACCEPT));
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
      if(server.getServerType() == WireProtocol.TCP) {
        acceptScheduler.execute(new AddToSelector(acceptScheduler, server, acceptSelector, 0));
        acceptSelector.wakeup();
      } else if(server.getServerType() == WireProtocol.UDP) {
        readScheduler.execute(new AddToSelector(readScheduler, server, readSelector, 0));
        writeScheduler.execute(new AddToSelector(writeScheduler, server, writeSelector, 0));
        readSelector.wakeup();
        writeSelector.wakeup();
      } else {
        throw new UnsupportedOperationException("Unknown Server WireProtocol!"+ server.getServerType());
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

  protected SocketExecuterByteStats writeableStats() {
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
        try {
          selector.close();
        } catch (IOException e) {
          ExceptionUtils.handleException(e);
        }
      }});
    selector.wakeup();
  }

  protected void doServerAccept(final Server server) {
    if(server != null) {
      try {
        final SocketChannel client = ((ServerSocketChannel)server.getSelectableChannel()).accept();
        if(client != null) {
          client.configureBlocking(false);
          server.acceptChannel(client);
        }
      } catch (IOException e) {
        server.close();
        ExceptionUtils.handleException(e);
      }
    }
  }

  protected void doClientConnect(final Client client, final Selector selector) {
    if(client == null) {
      return;
    }
    try {
      if(client.getChannel().finishConnect()) {
        client.setConnectionStatus(null);
      }
    } catch(IOException e) {
      client.close();
      client.setConnectionStatus(e);
      ExceptionUtils.handleException(e);
    }
  }

  protected void doClientWrite(final Client client, final Selector selector) {
    if(client != null) {
      try {
        final SelectionKey sk = client.getChannel().keyFor(selector);

        if((sk.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
          client.getChannel().register(selector, sk.interestOps() - SelectionKey.OP_WRITE);
        }
        client.doSocketWrite();
      } catch(Exception e) {
        client.close();
        ExceptionUtils.handleException(e);
      }
    }
  }

  protected void doClientRead(final Client client, final Selector selector) {
    if(client != null) {
      final SelectionKey sk = client.getChannel().keyFor(selector);
      try {
        if((sk.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
          client.getChannel().register(selector, sk.interestOps() - SelectionKey.OP_READ);
        }
        client.doSocketRead();
      } catch (ClosedChannelException e) {
        client.close();
        ExceptionUtils.handleException(e);
      }
    }
  }

  /**
   * This class is a helper runnable to generically add SelectableChannels to a selector for certain operations.
   * 
   */
  protected static class AddToSelector implements Runnable {
    final Client localClient;
    final Server localServer;
    final Selector localSelector;
    final int registerType;
    final Executor exec;

    public AddToSelector(final Executor exec, final Client client, final Selector selector, final int registerType) {
      this.exec = exec;
      localClient = client;
      localServer = null;
      localSelector = selector;
      this.registerType = registerType;
    }

    public AddToSelector(final Executor exec, final Server server, final Selector selector, final int registerType) {
      this.exec = exec;
      localClient = null;
      localServer = server;
      localSelector = selector;
      this.registerType = registerType;
    }

    private void runClient() {
      if(!localClient.isClosed()) {
        try {
          localSelector.wakeup();
          localClient.getChannel().register(localSelector, registerType);
        } catch (CancelledKeyException e) {
          exec.execute(this);
        } catch (ClosedChannelException e) {
          localClient.close();
        }
      }
    }

    private void runServer() {
      if(!localServer.isClosed()) {
        try {
          localServer.getSelectableChannel().register(localSelector, registerType);
        } catch (ClosedChannelException e) {
          ExceptionUtils.handleException(e);
          localServer.close();
        }
      }
    }

    @Override
    public void run() {
      if(localSelector.isOpen()) {
        if(localClient == null && localServer != null) {
          runServer();            
        } else if (localClient != null) {
          runClient();
        }
        localSelector.wakeup();
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
