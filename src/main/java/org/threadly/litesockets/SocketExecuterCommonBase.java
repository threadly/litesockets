package org.threadly.litesockets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

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
  protected static final int WATCHDOG_CLEANUP_TIME = 30000;
  protected final SubmitterScheduler acceptScheduler;
  protected final SubmitterScheduler readScheduler;
  protected final SubmitterScheduler writeScheduler;
  protected final SubmitterScheduler schedulerPool;
  protected final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  protected final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();
  protected final SocketExecuterByteStats stats = new SocketExecuterByteStats();
  protected final WatchdogCache dogCache;
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
  
  private boolean checkServer(final Server server) {
    try{
      checkRunning();
    } catch(Exception e) {
      return false;
    }
    if(server.isClosed() || server.getSocketExecuter() != this || !servers.containsKey(server.getSelectableChannel())) {
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
      } else if(server.getServerType() == WireProtocol.UDP) {
        acceptScheduler.execute(new AddToSelector(acceptScheduler, server, acceptSelector, SelectionKey.OP_READ));
      } else {
        throw new UnsupportedOperationException("Unknown Server WireProtocol!"+ server.getServerType());
      }
      acceptSelector.wakeup();
    }
  }
  
  
  @Override
  public void stopListening(final Server server) {
    if(!checkServer(server)) {
      return;
    } else {
      if(server.getServerType() == WireProtocol.TCP) {
        acceptScheduler.execute(new AddToSelector(acceptScheduler, server, acceptSelector, 0));
      } else if(server.getServerType() == WireProtocol.UDP) {
        acceptScheduler.execute(new AddToSelector(acceptScheduler, server, acceptSelector, 0));
      } else {
        throw new UnsupportedOperationException("Unknown Server WireProtocol!"+ server.getServerType());
      }
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

  protected static void closeSelector(final SubmitterScheduler scheduler, final Selector selector) {
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

  protected static void doServerAccept(final Server server) {
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

  protected static void doClientConnect(final Client client, final Selector selector) {
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

  protected static int doClientWrite(final Client client, final Selector selector) {
    int wrote = 0;
    if(client != null) {
      try {
        wrote = client.getChannel().write(client.getWriteBuffer());
        if(wrote > 0) {
          client.reduceWrite(wrote);
        }
        final SelectionKey sk = client.getChannel().keyFor(selector);
        if(! client.canWrite() && (sk.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
          client.getChannel().register(selector, sk.interestOps() - SelectionKey.OP_WRITE);
        } else {
          client.getChannel().register(selector, sk.interestOps());
        }
      } catch(Exception e) {
        client.close();
        ExceptionUtils.handleException(e);
      }
    }
    return wrote;
  }
  
  private static int doRead(ByteBuffer bb, SocketChannel sc) throws IOException {
    return sc.read(bb);
  }

  protected static int doClientRead(final Client client, final Selector selector) {
    int read = 0;
    if(client != null) {
      try {
        final ByteBuffer readByteBuffer = client.provideReadByteBuffer();
        final int origPos = readByteBuffer.position();
        read = doRead(readByteBuffer, client.getChannel());
        if(read < 0) {
          client.close();
        } else if( read > 0){
          readByteBuffer.position(origPos);
          final ByteBuffer resultBuffer = readByteBuffer.slice();
          readByteBuffer.position(origPos+read);
          resultBuffer.limit(read);
          client.addReadBuffer(resultBuffer);
          final SelectionKey sk = client.getChannel().keyFor(selector);
          if(! client.canRead() && (sk.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
            client.getChannel().register(selector, sk.interestOps() - SelectionKey.OP_READ);
          } else {
            client.getChannel().register(selector, sk.interestOps());
          }
        }
      } catch(Exception e) {
        client.close();
        ExceptionUtils.handleException(e);
      }
    }
    if(read >= 0) {
      return read;
    } else {
      return 0;
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
