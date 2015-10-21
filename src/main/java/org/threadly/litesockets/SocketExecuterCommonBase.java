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

import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.WatchdogCache;
import org.threadly.litesockets.tcp.TCPClient;
import org.threadly.litesockets.tcp.TCPServer;
import org.threadly.litesockets.udp.UDPServer;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.AbstractService;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.ExceptionUtils;


/**
 *  This is a common base class for the Threaded and NoThread SocketExecuters. 
 */
abstract class SocketExecuterCommonBase extends AbstractService implements SocketExecuter {
  public static final int WATCHDOG_CLEANUP_TIME = 30000;
  protected final SubmitterScheduler acceptScheduler;
  protected final SubmitterScheduler readScheduler;
  protected final SubmitterScheduler writeScheduler;
  protected final SubmitterScheduler schedulerPool;
  protected final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  protected final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();
  protected final SocketExecuterByteStats stats = new SocketExecuterByteStats();
  protected final WatchdogCache dogCache;
  public Selector readSelector;
  protected Selector writeSelector;
  protected Selector acceptSelector;

  SocketExecuterCommonBase(SubmitterScheduler scheduler) {
    this(scheduler,scheduler,scheduler,scheduler);
  }

  SocketExecuterCommonBase(SubmitterScheduler acceptScheduler, 
      SubmitterScheduler readScheduler, 
      SubmitterScheduler writeScheduler, 
      SubmitterScheduler ssi) {
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
  public TCPClient createTCPClient(String host, int port) throws IOException {
    checkRunning();
    TCPClient tc = new TCPClient(this, host, port);
    return tc;
  }
  
  
  @Override
  public TCPClient createTCPClient(SocketChannel sc) throws IOException {
    checkRunning();
    TCPClient tc = new TCPClient(this, sc);
    this.setClientOperations(tc);
    return tc;
  }
  
  @Override
  public TCPServer createTCPServer(String host, int port) throws IOException {
    checkRunning();
    TCPServer server = new TCPServer(this, host, port);
    return server;
  }
  
  @Override
  public TCPServer createTCPServer(ServerSocketChannel ssc) throws IOException {
    checkRunning();
    TCPServer server = new TCPServer(this, ssc);
    return server;
  }
  
  @Override
  public UDPServer createUDPServer(String host, int port) throws IOException {
    checkRunning();
    UDPServer server = new UDPServer(this, host, port);
    return server;
  }
  
  @Override
  public void startListening(Server server) {
    if(isRunning() && !servers.containsKey(server.getSelectableChannel())) {
      if(!server.isClosed() && server.getSocketExecuter() == this) {
        servers.put(server.getSelectableChannel(), server);
      }
    }
    if(servers.containsValue(server) && !server.isClosed() && isRunning()) {
      if(server.getServerType() == WireProtocol.TCP) {
        acceptScheduler.execute(new AddToSelector(server, acceptSelector, SelectionKey.OP_ACCEPT));
      } else if(server.getServerType() == WireProtocol.UDP) {
        acceptScheduler.execute(new AddToSelector(server, acceptSelector, SelectionKey.OP_READ));
      }
      acceptSelector.wakeup();
    } else if(server.isClosed()) {
      servers.remove(server.getSelectableChannel());
    }
  }
  
  
  @Override
  public void stopListening(Server server) {
    if(servers.containsValue(server) && !server.isClosed() && isRunning()) {
      if(server.getServerType() == WireProtocol.TCP) {
        acceptScheduler.execute(new AddToSelector(server, acceptSelector, 0));
      } else if(server.getServerType() == WireProtocol.UDP) {
        acceptScheduler.execute(new AddToSelector(server, acceptSelector, 0));
      }
      acceptSelector.wakeup();
    } else if(server.isClosed()) {
      servers.remove(server.getSelectableChannel());
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
  public void watchFuture(ListenableFuture<?> lf, long delay) {
    dogCache.watch(lf, delay);
  }

  protected static Selector openSelector() {
    try {
      return Selector.open();
    } catch (IOException e) {
      throw new StartupException(e);
    }
  }

  protected static void closeSelector(SubmitterScheduler scheduler, final Selector selector) {
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

  protected static void doServerAccept(Server server) {
    if(server != null) {
      try {
        SocketChannel client = ((ServerSocketChannel)server.getSelectableChannel()).accept();
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

  protected static void doClientConnect(Client client, Selector selector) {
    if(client != null) {
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
  }

  protected static int doClientWrite(Client client, Selector selector) {
    int wrote = 0;
    if(client != null) {
      try {
        wrote = client.getChannel().write(client.getWriteBuffer());
        if(wrote > 0) {
          client.reduceWrite(wrote);
        }
        SelectionKey sk = client.getChannel().keyFor(selector);
        if(! client.canWrite() && (sk.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
          client.getChannel().register(selector, sk.interestOps() - SelectionKey.OP_WRITE);
        }
      } catch(Exception e) {
        client.close();
        ExceptionUtils.handleException(e);
      }
    }
    return wrote;
  }

  protected static int doClientRead(Client client, Selector selector) {
    int read = 0;
    if(client != null) {
      try {
        ByteBuffer readByteBuffer = client.provideReadByteBuffer();
        int origPos = readByteBuffer.position();
        read = client.getChannel().read(readByteBuffer);
        if(read < 0) {
          client.close();
        } else if( read > 0){
          readByteBuffer.position(origPos);
          ByteBuffer resultBuffer = readByteBuffer.slice();
          readByteBuffer.position(origPos+read);
          resultBuffer.limit(read);
          client.addReadBuffer(resultBuffer);
          SelectionKey sk = client.getChannel().keyFor(selector);
          if(! client.canRead() && (sk.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
            client.getChannel().register(selector, sk.interestOps() - SelectionKey.OP_READ);
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

  protected static void flushSelector(Selector selector) {
    try {
      selector.selectNow();
    } catch (IOException e) {
      ExceptionUtils.handleException(e);
    }
  }

  /**
   * This exception in thrown when we have problems doing common operations during startup.
   * This is usually around opening selectors.
   */
  public static class StartupException extends RuntimeException {

    private static final long serialVersionUID = 358704530394209047L;
    public StartupException(Throwable t) {
      super(t);
    }

  }


  /**
   * This class is a helper runnable to generically remove SelectableChannels from a selector.
   * 
   *
   */
  protected static class RemoveFromSelector implements Runnable {
    SelectableChannel localChannel;
    Selector localSelector;

    public RemoveFromSelector(SelectableChannel channel, Selector selector) {
      localChannel = channel;
      localSelector = selector;
    }

    @Override
    public void run() {
      if(localSelector.isOpen()) {
        SelectionKey sk = localChannel.keyFor(localSelector);
        if(sk != null) {
          sk.cancel();
          flushSelector(localSelector);
        }
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

    public AddToSelector(Client client, Selector selector, int registerType) {
      localClient = client;
      localServer = null;
      localSelector = selector;
      this.registerType = registerType;
    }

    public AddToSelector(Server server, Selector selector, int registerType) {
      localClient = null;
      localServer = server;
      localSelector = selector;
      this.registerType = registerType;
    }

    @Override
    public void run() {
      if(localSelector.isOpen()) {
        try {
          if(localClient != null && !localClient.isClosed()) {
            localClient.getChannel().register(localSelector, registerType);
          } else if (localServer != null) {
            localServer.getSelectableChannel().register(localSelector, registerType);
          }
          localSelector.wakeup();
        } catch (ClosedChannelException e) {
          if(localClient != null) {
            localClient.close();
          } else if (localServer != null) {
            localServer.close();
          }
          ExceptionUtils.handleException(e);
        } catch (CancelledKeyException e) {
          ExceptionUtils.handleException(e);
        }
      }
    }
  }


  /**
   * Implementation of the SimpleByteStats.
   */
  protected static class SocketExecuterByteStats extends SimpleByteStats {
    @Override
    protected void addWrite(int size) {
      super.addWrite(size);
    }

    @Override
    protected void addRead(int size) {
      super.addRead(size);
    }
  }

}
