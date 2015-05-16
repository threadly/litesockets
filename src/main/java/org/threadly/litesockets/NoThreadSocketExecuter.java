package org.threadly.litesockets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.NoThreadScheduler;
import org.threadly.concurrent.SchedulerServiceInterface;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.ThreadedSocketExecuter.SocketExecuterByteStats;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.litesockets.utils.WatchdogCache;
import org.threadly.util.AbstractService;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.ExceptionUtils;

/**
 * <p>The NoThreadSocketExecuter is a simpler implementation of a {@link SocketExecuterInterface} 
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
 * <p>The functions like {@link #addClient(Client)}, {@link #removeClient(Client)}, {@link #addServer(Server)}, and 
 * {@link #removeServer(Server)} can be called from other threads safely.</p>
 * 
 * @author lwahlmeier
 */
public class NoThreadSocketExecuter extends AbstractService implements SocketExecuterInterface {
  private final NoThreadScheduler scheduler = new NoThreadScheduler();
  private final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  private final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();
  private final SocketExecuterByteStats stats = new SocketExecuterByteStats();
  private final WatchdogCache dogCache = new WatchdogCache(scheduler);
  private volatile Selector selector;

  /**
   * Constructs a NoThreadSocketExecuter.  {@link #start()} must still be called before using it.
   */
  public NoThreadSocketExecuter() {
  }

  /**
   * This is used to wakeup the {@link Selector} assuming it was called with a timeout on it.
   * Most all methods in this class that need to do a wakeup do it automatically, but
   * there are situations where you might want to wake up the thread we are blocked on 
   * manually.
   */
  public void wakeup() {
    if(isRunning()) {
      selector.wakeup();
    }
  }

  @Override
  public void addClient(final Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(! client.isClosed() && client.getProtocol() == WireProtocol.TCP && isRunning()) {
      client.setClientsThreadExecutor(scheduler);
      client.setClientsSocketExecuter(this);
      if(client.getChannel() != null && client.getChannel().isConnected()) {
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          if(client.canRead() && client.canWrite()) {
            scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE));
          } else if(client.canRead() ) {
            scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_READ));
          } else if(client.canWrite()) {
            scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_WRITE));  
          }
          selector.wakeup();
        }
      } else {
        if(client.getChannel() == null) {
          client.connect();
        }
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_CONNECT));
          dogCache.watch(client.connect(), client.getTimeout());
          selector.wakeup();
        }
      }
    }
  }

  @Override
  public void removeClient(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning()) {
      Client c = clients.remove(client.getChannel());
      if(c != null) {
        SelectionKey sk = client.getChannel().keyFor(selector);
        if(sk != null) {
          scheduler.execute(new RemoveFromSelector(client.getChannel(), selector));
          selector.wakeup();
        }
      }
    }
  }

  @Override
  public void addServer(final Server server) {
    ArgumentVerifier.assertNotNull(server, "Server");
    if(isRunning() && server.getSelectableChannel().isOpen()) {
      Server sn = servers.putIfAbsent(server.getSelectableChannel(), server);
      if(sn == null) {
        server.setSocketExecuter(this);
        server.setThreadExecutor(scheduler);
        if(server.getServerType() == WireProtocol.TCP) {
          scheduler.execute(new AddToSelector(server.getSelectableChannel(), selector, SelectionKey.OP_ACCEPT));
        } else if (server.getServerType() == WireProtocol.UDP) {
          scheduler.execute(new AddToSelector(server.getSelectableChannel(), selector, SelectionKey.OP_READ));
        }
        selector.wakeup();
      }
    }
  }

  @Override
  public void removeServer(Server server) {
    ArgumentVerifier.assertNotNull(server, "Server");
    if(isRunning()) {
      Server s = servers.remove(server.getSelectableChannel());
      if(s != null) {
        scheduler.execute(new RemoveFromSelector(server.getSelectableChannel(), selector));
        selector.wakeup();
      }
    }
  }

  @Override
  public void flagNewWrite(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning() && clients.containsKey(client.getChannel())) {
      if(client.canRead()) {
        scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_WRITE|SelectionKey.OP_READ));
      } else {
        scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_WRITE));
      }
      selector.wakeup();
    }
  }

  @Override
  public void flagNewRead(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning() && clients.containsKey(client.getChannel())) {
      if(client.canWrite()) {
        scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_WRITE|SelectionKey.OP_READ));
      } else {
        scheduler.execute(new AddToSelector(client.getChannel(), selector, SelectionKey.OP_READ));
      }
      selector.wakeup();
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
  public SchedulerServiceInterface getThreadScheduler() {
    return this.scheduler;
  }

  @Override
  protected void startupService() {
    try {
      selector = Selector.open();
      dogCache.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void shutdownService() {
    scheduler.clearTasks();
    selector.wakeup();
    selector.wakeup();
    if(selector != null && selector.isOpen()) {
      scheduler.execute(new Runnable() {
        @Override
        public void run() {
          try {
            selector.close();
          } catch (Exception e) {
            ExceptionUtils.handleException(e);
          }
        }});
    }
    clients.clear();
    servers.clear();
    dogCache.stop();
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
  public void select(int delay) {
    ArgumentVerifier.assertNotNegative(delay, "delay");
    if(isRunning()) {
      scheduler.tick(null);
      try {
        if(delay == 0) {
          selector.selectNow();
        } else {
          selector.select(delay);
        }
        for(SelectionKey key: selector.selectedKeys()) {
          try {
            if (key.isConnectable() ) {
              SocketChannel sc = (SocketChannel)key.channel();
              final Client client = clients.get(sc);
              if(sc.isConnectionPending()) {
                try {
                  boolean tmp = sc.finishConnect();
                  if(tmp) {
                    client.setConnectionStatus(null);
                    if(client.canWrite()) {
                      flagNewWrite(client);
                    } else if (client.canRead()) {
                      flagNewRead(client);
                    }
                  }
                } catch(IOException e) {
                  client.setConnectionStatus(e);
                  removeClient(client);
                  client.close();
                }
              }
            }
            if(key.isAcceptable()) {
              ServerSocketChannel server = (ServerSocketChannel) key.channel();
              doAccept(server);
            } else if(key.isReadable()) {
              doRead(key.channel());
            } else if(key.isWritable()) {
              SocketChannel sc = (SocketChannel)key.channel();
              doWrite(sc);
            }
          } catch(CancelledKeyException e) {
            //Key could be cancelled at any point, we dont really care about it.
          }
        }
      } catch (IOException e) {
        //There is really nothing to do here but try again, usually this is because of shutdown.
      } catch(ClosedSelectorException e) {
        //We do nothing here because the next loop should not happen now.
      } catch (NullPointerException e) {
        //There is a bug in some JVMs around this where the select() can throw an NPE from native code.
      }
      scheduler.tick(null);
    }
  }

  private void doAccept(ServerSocketChannel server) {
    final Server tServer = servers.get(server);
    try {
      SocketChannel client = server.accept();
      if(client != null) {
        client.configureBlocking(false);
        tServer.acceptChannel(client);
      }
    } catch (IOException e) {
      removeServer(tServer);
      tServer.close();
    }    
  }

  private void doRead(SelectableChannel sc) {
    final Client client = clients.get(sc);
    if(client != null) {
      try {
        ByteBuffer readByteBuffer = client.provideReadByteBuffer();
        int origPos = readByteBuffer.position();
        int read = client.getChannel().read(readByteBuffer);
        if(read < 0) {
          removeClient(client);
          client.close();
        } else if( read > 0) {
          stats.addRead(read);
          readByteBuffer.position(origPos);
          ByteBuffer resultBuffer = readByteBuffer.slice();
          readByteBuffer.position(origPos+read);
          resultBuffer.limit(read);
          client.addReadBuffer(resultBuffer.asReadOnlyBuffer());
          //client.callReader();
          if(! client.canWrite()  && ! client.canRead()) {
            client.getChannel().register(selector, 0);
          } else if (! client.canRead()  ) {
            client.getChannel().register(selector, SelectionKey.OP_WRITE);
          }
        } 
      } catch(IOException e) {
        removeClient(client);
        client.close();
      }
    }else {
      final Server server = servers.get(sc);
      if(server != null && server.getServerType() == WireProtocol.UDP) {
        server.acceptChannel((DatagramChannel)server.getSelectableChannel());
      }
    }
  }

  private void doWrite(SocketChannel sc) {
    final Client client = clients.get(sc);
    if(client != null) {
      try {
        int writeSize = sc.write(client.getWriteBuffer());
        stats.addWrite(writeSize);
        client.reduceWrite(writeSize);
        if(! client.canWrite()  && ! client.canRead()) {
          client.getChannel().register(selector, 0);
        } else if (! client.canWrite()  ) {
          client.getChannel().register(selector, SelectionKey.OP_READ);
        }
      } catch(IOException e) {
        removeClient(client);
        client.close();
      }
    }
  }

  private void flushOutSelector() {
    try {
      selector.selectNow();
    } catch (IOException e) {
    }
  }

  /**
   * This class is a helper runnable to generically remove SelectableChannels from a selector.
   * 
   *
   */
  private class RemoveFromSelector implements Runnable {
    SelectableChannel localChannel;
    Selector localSelector;

    public RemoveFromSelector(SelectableChannel channel, Selector selector) {
      localChannel = channel;
      localSelector = selector;
    }

    @Override
    public void run() {
      if(isRunning()) {
        SelectionKey sk = localChannel.keyFor(localSelector);
        if(sk != null) {
          sk.cancel();
          flushOutSelector();
        }
      }
    }
  }

  /**
   * This class is a helper runnable to generically add SelectableChannels to a selector for certain operations.
   * 
   */
  private class AddToSelector implements Runnable {
    SelectableChannel localChannel;
    Selector localSelector;
    int registerType;

    public AddToSelector(SelectableChannel channel, Selector selector, int registerType) {
      localChannel = channel;
      localSelector = selector;
      this.registerType = registerType;
    }

    @Override
    public void run() {
      if(isRunning()) {
        try {
          flushOutSelector();
          localChannel.register(localSelector, registerType);
        } catch (ClosedChannelException e) {
          removeChannel();
        } catch (CancelledKeyException e) {

        }
      }
    }

    private void removeChannel() {
      Client client = clients.remove(localChannel);
      Server server = servers.remove(localChannel);
      if(client != null) {
        removeClient(client);
        client.close();
      } else if (server != null){
        removeServer(server);
        server.close();
      }
    }
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
