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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import org.threadly.concurrent.AbstractService;
import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.KeyDistributedExecutor;
import org.threadly.concurrent.ScheduledExecutorServiceWrapper;
import org.threadly.concurrent.SimpleSchedulerInterface;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.util.ArgumentVerifier;


/**
 * <p>This is a mutliThreaded implementation of a {@link SocketExecuterInterface}.  It uses separate threads to perform Accepts, Reads and Writes.  
 * Constructing this will create 3 additional threads.  Generally only one of these will ever be needed in a process.</p>
 * 
 * <p>This is generally the {@link SocketExecuterInterface} implementation you want to use for servers, especially if they have to deal with more
 * then just a few connections.  See {@link NoThreadSocketExecuter} for a more efficient implementation when not dealing with many connections.</p>
 * 
 * @author lwahlmeier
 *
 */
public class ThreadedSocketExecuter extends AbstractService implements SocketExecuterInterface {
  private final SingleThreadScheduler acceptScheduler = new SingleThreadScheduler(new ConfigurableThreadFactory("SocketAcceptor", false, true, Thread.currentThread().getPriority(), null, null));
  private final SingleThreadScheduler readScheduler = new SingleThreadScheduler(new ConfigurableThreadFactory("SocketReader", false, true, Thread.currentThread().getPriority(), null, null));
  private final SingleThreadScheduler writeScheduler = new SingleThreadScheduler(new ConfigurableThreadFactory("SocketWriter", false, true, Thread.currentThread().getPriority(), null, null));
  private final KeyDistributedExecutor clientDistributer;
  private final SimpleSchedulerInterface schedulerPool;
  private final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  private final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();

  protected volatile long readThreadID = 0;
  protected Selector readSelector;
  protected Selector writeSelector;
  protected Selector acceptSelector;

  private AcceptRunner acceptor;
  private ReadRunner reader;
  private WriteRunner writer;


  /**
   * <p>This constructor creates its own {@link SingleThreadScheduler} Threadpool to use for client operations.  This is generally 
   * not recommended unless you are not doing many socket connections/operations.  You should really use your own multiThreaded 
   * thread pool.</p>
   */
  public ThreadedSocketExecuter() {
    schedulerPool = new SingleThreadScheduler(new ConfigurableThreadFactory("SocketClientThread", false, true, Thread.currentThread().getPriority(), null, null));
    clientDistributer = new KeyDistributedExecutor(schedulerPool);
  }

  /**
   * <p>This is provided to allow people to use java's generic threadpool scheduler {@link ScheduledExecutorService} </p>
   * 
   * @param exec The {@link ScheduledExecutorService} to be used for client/server callbacks.
   */
  public ThreadedSocketExecuter(ScheduledExecutorService exec) {
    ArgumentVerifier.assertNotNull(exec, "ScheduledExecutorService");
    schedulerPool = new ScheduledExecutorServiceWrapper(exec);
    clientDistributer = new KeyDistributedExecutor(schedulerPool);
  }
  
  /**
   * <p>Here you can provide a {@link ScheduledExecutorService} for this {@link SocketExecuterInterface}.  This will be used
   * on accept, read, and close callback events.</p>
   * 
   * @param exec the {@link ScheduledExecutorService} to be used for client/server callbacks.
   */
  public ThreadedSocketExecuter(SimpleSchedulerInterface exec) {
    ArgumentVerifier.assertNotNull(exec, "SimpleSchedulerInterface");
    schedulerPool = exec;
    clientDistributer = new KeyDistributedExecutor(schedulerPool);
  }

  @Override
  public void addClient(final Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(! client.isClosed() && client.getProtocol() == WireProtocol.TCP && isRunning()) {
      client.setClientsThreadExecutor(clientDistributer.getSubmitterForKey(client));
      client.setClientsSocketExecuter(this);
      if(client.getChannel() != null && client.getChannel().isConnected()) {
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          if(client.canRead()) {
            readScheduler.execute(new AddToSelector(client, readSelector, SelectionKey.OP_READ));
            readSelector.wakeup();
          }
          if(client.canWrite()) {
            writeScheduler.execute(new AddToSelector(client, writeSelector, SelectionKey.OP_WRITE));
            writeSelector.wakeup();  
          }
        }
      } else {
        if(client.getChannel() == null) {
          client.connect();
        }
        Client nc = clients.putIfAbsent(client.getChannel(), client);
        if(nc == null) {
          readScheduler.execute(new AddToSelector(client, readSelector, SelectionKey.OP_CONNECT));
          readSelector.wakeup();
          schedulerPool.schedule(new Runnable() {
            @Override
            public void run() {
              if(client.hasConnectionTimedOut()) {
                SelectionKey sk = client.getChannel().keyFor(readSelector);
                if(sk != null) {
                  sk.cancel();
                }
                removeClient(client);
                client.close();
                client.setConnectionStatus(new TimeoutException("Timed out while connecting!"));
              }
            }}, client.getTimeout()+100);
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
        SelectionKey sk = client.getChannel().keyFor(readSelector);
        SelectionKey sk2 = client.getChannel().keyFor(writeSelector);
        if(sk != null) {
          sk.cancel();
          readSelector.wakeup();
        }
        if(sk2 != null) {
          sk2.cancel();
          writeSelector.wakeup();
        }
      }
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
        acceptScheduler.execute(new Runnable() {
          @Override
          public void run() {
            SelectionKey key = server.getSelectableChannel().keyFor(acceptSelector);
            if(key == null) {
              try {
                if(server.getServerType() == WireProtocol.TCP) {
                  server.getSelectableChannel().register(acceptSelector, SelectionKey.OP_ACCEPT);
                } else if(server.getServerType() == WireProtocol.UDP) {
                  server.getSelectableChannel().register(acceptSelector, SelectionKey.OP_READ);
                }
              } catch (ClosedChannelException e) {
                removeServer(server);
                server.close();
              }
            }
          }});
        acceptSelector.wakeup();
      }
    }
  }

  @Override
  public void removeServer(Server server) {
    ArgumentVerifier.assertNotNull(server, "Server");
    if(isRunning()) {
      servers.remove(server.getSelectableChannel());
      SelectionKey key = null;
      if(server.getServerType() == WireProtocol.TCP) {
        key = server.getSelectableChannel().keyFor(acceptSelector);
      } else {
        key = server.getSelectableChannel().keyFor(readSelector);
      }
      if(key != null && key.isValid()) {
        key.cancel();
      }
      acceptSelector.wakeup();
      readSelector.wakeup();
    }
  }

  @Override
  protected void startupService() {
    try {
      acceptSelector = Selector.open();
      readSelector   = Selector.open();
      writeSelector  = Selector.open();
      acceptor = new AcceptRunner();
      reader   = new ReadRunner();
      writer   = new WriteRunner();
      acceptScheduler.execute(acceptor);
      readScheduler.execute(reader);
      writeScheduler.execute(writer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  protected void shutdownService() {
    acceptScheduler.shutdownNow();
    readScheduler.shutdownNow();
    writeScheduler.shutdownNow();
    try {
      acceptSelector.close();
    } catch (IOException e) {
      //Dont care
    }
    try {
      readSelector.close();
    } catch (IOException e) {
    }
    try {
      writeSelector.close();
    } catch (IOException e) {
    }
    clients.clear();
    servers.clear();

  }

  @Override
  public void flagNewWrite(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning() && clients.containsKey(client.getChannel())) {
      writeScheduler.execute(new AddToSelector(client, writeSelector, SelectionKey.OP_WRITE));
      writeSelector.wakeup();
    }
  }

  @Override
  public void flagNewRead(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning() && clients.containsKey(client.getChannel())) {
      readScheduler.execute(new AddToSelector(client, readSelector, SelectionKey.OP_READ));
      readSelector.wakeup();
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

  
  /**
   * <p>This is used to figure out if the current used thread is the SocketExecuters ReadThread.
   * This is used by clients to Enforce certain threads to do certain public tasks.</p>
   * 
   * 
   * @return a boolean to tell you if the current thread is the readThread for this executer.
   */
  public boolean verifyReadThread() {
    long tid = Thread.currentThread().getId();
    if(tid != readThreadID) {
      return false;
    }
    return true;
  }

  private class AcceptRunner implements Runnable {
    @Override
    public void run() {
      if(isRunning()) {
        try {
          acceptSelector.selectedKeys().clear();
          acceptSelector.select();
          if(isRunning()) {
            for(SelectionKey sk: acceptSelector.selectedKeys()) {
                if(sk.isAcceptable()) {
                  ServerSocketChannel server = (ServerSocketChannel) sk.channel();
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
                } else if(sk.isReadable()) {
                  DatagramChannel server = (DatagramChannel) sk.channel();
                  final Server udpServer = servers.get(server);
                  udpServer.acceptChannel(server);
                }
            }
          }
        } catch (IOException e) {
          stopIfRunning();
        } catch (ClosedSelectorException e) {
          stopIfRunning();
        } finally {
          if(isRunning()) {
            acceptScheduler.execute(this);
          }
        }
      }
    }
  }

  private class ReadRunner implements Runnable {
    @Override
    public void run() {
      if(isRunning()) {
        if(readThreadID == 0) {
          readThreadID = Thread.currentThread().getId();
        }
        try {
          readSelector.selectedKeys().clear();
          readSelector.select();
          if(isRunning() && ! readSelector.selectedKeys().isEmpty()) {
            for(SelectionKey sk: readSelector.selectedKeys()) {
              SocketChannel sc = (SocketChannel)sk.channel();
              final Client client = clients.get(sc);
              if(sc.isConnectionPending()) {
                try {
                  if(sc.finishConnect()) {
                    client.setConnectionStatus(null);
                    client.getChannel().register(readSelector, SelectionKey.OP_READ);
                    if(client.canWrite()) {
                      flagNewWrite(client);
                    }
                  }
                } catch(IOException e) {
                  client.setConnectionStatus(e);
                  removeClient(client);
                  client.close();
                }
              }
              if(client != null) {
                try {
                  ByteBuffer readByteBuffer = client.provideReadByteBuffer();
                  int origPos = readByteBuffer.position();
                  int read = sc.read(readByteBuffer);
                  if(read < 0) {
                    removeClient(client);
                    client.close();
                  } else if( read > 0){
                    readByteBuffer.position(origPos);
                    ByteBuffer resultBuffer = readByteBuffer.slice();
                    readByteBuffer.position(origPos+read);
                    resultBuffer.limit(read);
                    client.addReadBuffer(resultBuffer.asReadOnlyBuffer());
                    if(! client.canRead()) {
                      client.getChannel().register(readSelector, 0);
                    }
                  }
                } catch(Exception e) {
                  removeClient(client);
                  client.close();
                }
              }
            }
          }
        } catch (IOException e) {
          stopIfRunning();
        } finally {
          if(isRunning()) {
            readScheduler.execute(this);
          }
        }        
      }
    }
  }

  private class WriteRunner implements Runnable {
    @Override
    public void run() {
      if(isRunning()) {
        try {
          writeSelector.selectedKeys().clear();
          writeSelector.select();
          if(isRunning() && ! writeSelector.selectedKeys().isEmpty()) {
            for(SelectionKey sk: writeSelector.selectedKeys()) {
              SocketChannel sc = (SocketChannel)sk.channel();
              final Client client = clients.get(sc);
              if(client != null) {
                try {
                  int writeSize = sc.write(client.getWriteBuffer());
                  client.reduceWrite(writeSize);
                  if(! client.canWrite()) {
                    client.getChannel().register(writeSelector, 0);
                  }
                } catch(Exception e) {
                  removeClient(client);
                  client.close();
                }
              }
            }

          }
        } catch (IOException e) {
          stopIfRunning();
        } finally {
          if(isRunning()) {
            writeScheduler.execute(this);
          }
        }
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

  @Override
  public SimpleSchedulerInterface getThreadScheduler() {
    return schedulerPool;
  }
}
