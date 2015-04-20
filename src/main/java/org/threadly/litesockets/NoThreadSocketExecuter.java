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
import java.util.concurrent.TimeoutException;

import org.threadly.concurrent.AbstractService;
import org.threadly.concurrent.NoThreadScheduler;
import org.threadly.concurrent.SchedulerServiceInterface;

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
 *
 */
public class NoThreadSocketExecuter extends AbstractService implements SocketExecuterInterface {
  private final NoThreadScheduler scheduler = new NoThreadScheduler(false);
  private final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  private final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();
  private Selector selector;

  /**
   * Constructs a NoThreadSocketExecuter.  {@link #start()} must still be called before using it.
   * 
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
    if(! client.isClosed() && client.getProtocol() == WireProtocol.TCP && isRunning()) {
      client.setClientsThreadExecutor(scheduler);
      client.setClientsSocketExecuter(this);
      if(client.getChannel() != null && client.getChannel().isConnected()) {
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          if(client.canRead() && client.canWrite()) {
            scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE));
          } else if(client.canRead() ) {
            scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_READ));
          } else if(client.canWrite()) {
            scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_WRITE));  
          }
          selector.wakeup();
        }
      } else {
        if(client.getChannel() == null) {
          client.connect();
        }
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_CONNECT));
          scheduler.schedule(new Runnable() {
            @Override
            public void run() {
              if(client.hasConnectionTimedOut()) {
                SelectionKey sk = client.getChannel().keyFor(selector);
                if(sk != null) {
                  sk.cancel();
                }
                removeClient(client);
                client.close();
                client.setConnectionStatus(new TimeoutException("Timed out while connecting!"));
              }
            }}, client.getTimeout()+10);
          selector.wakeup();
        }
      }
    }
  }

  @Override
  public void removeClient(Client client) {
    if(isRunning()) {
      Client c = clients.remove(client.getChannel());
      if(c != null) {
        SelectionKey sk = client.getChannel().keyFor(selector);
        if(sk != null) {
          sk.cancel();
        }
      }
    }
  }

  @Override
  public void addServer(final Server server) {
    if(isRunning() && server.getSelectableChannel().isOpen()) {
      Server sn = servers.putIfAbsent(server.getSelectableChannel(), server);
      if(sn == null) {
        server.setSocketExecuter(this);
        server.setThreadExecutor(scheduler);
        scheduler.execute(new Runnable() {
          @Override
          public void run() {
            SelectionKey key = server.getSelectableChannel().keyFor(selector);
            if(key == null) {
              try {
                if(server.getServerType() == WireProtocol.TCP) {
                  server.getSelectableChannel().register(selector, SelectionKey.OP_ACCEPT);
                } else if(server.getServerType() == WireProtocol.UDP) {
                  server.getSelectableChannel().register(selector, SelectionKey.OP_READ);
                }
                selector.wakeup();
              } catch (ClosedChannelException e) {
                removeServer(server);
                server.close();
              } catch(ClosedSelectorException e) {
                
              }
            }
          }});
      }
    }
  }

  @Override
  public void removeServer(Server server) {
    if(isRunning()) {
      servers.remove(server.getSelectableChannel());
      selector.wakeup();
    }
  }

  @Override
  public void flagNewWrite(Client client) {
    if(isRunning() && clients.containsKey(client.getChannel())) {
      if(client.canRead()) {
        scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_WRITE|SelectionKey.OP_READ));
      } else {
        scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_WRITE));
      }
      selector.wakeup();
    }
  }

  @Override
  public void flagNewRead(Client client) {
    if(isRunning() && clients.containsKey(client.getChannel())) {
      if(client.canWrite()) {
        scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_WRITE|SelectionKey.OP_READ));
      } else {
        scheduler.execute(new AddToSelector(client, selector, SelectionKey.OP_READ));
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
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void shutdownService() {
    scheduler.clearTasks();
    selector.wakeup();
    selector.wakeup();
    try {
      if(selector != null && selector.isOpen()) {
        selector.close();
      }
    } catch (IOException e) {

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
  public void select(int delay) {
    if(isRunning()) {
      try {
        scheduler.tick(null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
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
            //Key could be cancelled at any point...
          }
        }
      } catch (IOException e) {

      } catch(ClosedSelectorException e) {
        
      }
      try {
        scheduler.tick(null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
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
      if(server.getServerType() == WireProtocol.UDP) {
        server.acceptChannel((DatagramChannel)server.getSelectableChannel());
      }
    }

  }

  private void doWrite(SocketChannel sc) {
    final Client client = clients.get(sc);
    if(client != null) {
      try {
        int writeSize = sc.write(client.getWriteBuffer());
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

  private class AddToSelector implements Runnable {
    Client local_client;
    Selector local_selector;
    int registerType;

    public AddToSelector(Client client, Selector selector, int registerType) {
      local_client = client;
      local_selector = selector;
      this.registerType = registerType;
    }

    @Override
    public void run() {
      if(isRunning()) {
        try {
          local_client.getChannel().register(local_selector, registerType);
        } catch (ClosedChannelException e) {
          removeClient(local_client);
          local_client.close();
        } catch (CancelledKeyException e) {
          removeClient(local_client);
        }
      }
    }
  }
}
