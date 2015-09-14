package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.KeyDistributedExecutor;
import org.threadly.concurrent.ScheduledExecutorServiceWrapper;
import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.util.ArgumentVerifier;

/**
 * <p>This is a mutliThreaded implementation of a {@link SocketExecuter}.  It uses separate threads to perform Accepts, Reads and Writes.  
 * Constructing this will create 3 additional threads.  Generally only one of these will ever be needed in a process.</p>
 * 
 * <p>This is generally the {@link SocketExecuter} implementation you want to use for servers, especially if they have to deal with more
 * then just a few connections.  See {@link NoThreadSocketExecuter} for a more efficient implementation when not dealing with many connections.</p>
 * 
 * @author lwahlmeier
 *
 */
public class ThreadedSocketExecuter extends SocketExecuterCommonBase {
  private final KeyDistributedExecutor clientDistributer;
  private final SingleThreadScheduler localAcceptScheduler;
  private final SingleThreadScheduler localReadScheduler;
  private final SingleThreadScheduler localWriteScheduler;

  protected volatile long readThreadID = 0;

  private final AcceptRunner acceptor = new AcceptRunner();
  private final ReadRunner reader = new ReadRunner();
  private final WriteRunner writer = new WriteRunner();


  /**
   * <p>This constructor creates its own {@link SingleThreadScheduler} Threadpool to use for client operations.  This is generally 
   * not recommended unless you are not doing many socket connections/operations.  You should really use your own multiThreaded 
   * thread pool.</p>
   */
  public ThreadedSocketExecuter() {
    this(new SingleThreadScheduler(
        new ConfigurableThreadFactory(
            "SocketClientThread", false, true, Thread.currentThread().getPriority(), null, null)));
  }

  /**
   * <p>This is provided to allow people to use java's generic threadpool scheduler {@link ScheduledExecutorService}.</p>
   * 
   * @param exec The {@link ScheduledExecutorService} to be used for client/server callbacks.
   */
  public ThreadedSocketExecuter(ScheduledExecutorService exec) {
    this(new ScheduledExecutorServiceWrapper(exec));
  }

  /**
   * <p>Here you can provide a {@link ScheduledExecutorService} for this {@link SocketExecuter}.  This will be used
   * on accept, read, and close callback events.</p>
   * 
   * @param exec the {@link ScheduledExecutorService} to be used for client/server callbacks.
   */
  public ThreadedSocketExecuter(SubmitterScheduler exec) {
    super(new SingleThreadScheduler(new ConfigurableThreadFactory("SocketAccept", false, true, Thread.currentThread().getPriority(), null, null)),
        new SingleThreadScheduler(new ConfigurableThreadFactory("SocketReader", false, true, Thread.currentThread().getPriority(), null, null)),
        new SingleThreadScheduler(new ConfigurableThreadFactory("SocketWriter", false, true, Thread.currentThread().getPriority(), null, null)),
        exec);
    localAcceptScheduler = (SingleThreadScheduler) this.acceptScheduler;
    localReadScheduler = (SingleThreadScheduler) this.readScheduler;
    localWriteScheduler = (SingleThreadScheduler) this.writeScheduler;
    clientDistributer = new KeyDistributedExecutor(schedulerPool);
  }

  @Override
  protected void startupService() {
    acceptSelector = openSelector();
    readSelector = openSelector();
    writeSelector = openSelector();
    acceptScheduler.execute(acceptor);
    readScheduler.execute(reader);
    writeScheduler.execute(writer);
  }

  @Override
  protected void shutdownService() {
    for(Client client: clients.values()) {
      client.close();
    }
    for(Server server: servers.values()) {
      server.close();
    }
    closeSelector(localAcceptScheduler, acceptSelector);
    closeSelector(localReadScheduler, readSelector);
    closeSelector(localWriteScheduler, writeSelector);
  }  
  
  @Override
  public void setClientOperations(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(isRunning() && !clients.containsKey(client.getChannel())) {
      if(!client.isClosed() && client.getClientsSocketExecuter() == this) {
        clients.put(client.getChannel(), client);
      }
    }
    if(clients.containsKey(client.getChannel()) && !client.isClosed() && isRunning()) {
      if(!client.getChannel().isConnected() && client.getChannel().isConnectionPending()) {
        readScheduler.execute(new AddToSelector(client, readSelector, SelectionKey.OP_CONNECT));
        writeScheduler.execute(new AddToSelector(client, writeSelector, 0));
      } else if(client.canWrite() && client.canRead()) {
        writeScheduler.execute(new AddToSelector(client, writeSelector, SelectionKey.OP_WRITE));
        readScheduler.execute(new AddToSelector(client, readSelector, SelectionKey.OP_READ));
      } else if (client.canRead()){
        readScheduler.execute(new AddToSelector(client, readSelector, SelectionKey.OP_READ));
        writeScheduler.execute(new AddToSelector(client, writeSelector, 0));
      } else if (client.canWrite()){
        writeScheduler.execute(new AddToSelector(client, writeSelector, SelectionKey.OP_WRITE));
        readScheduler.execute(new AddToSelector(client, readSelector, 0));
      } else {
        writeScheduler.execute(new AddToSelector(client, writeSelector, 0));
        readScheduler.execute(new AddToSelector(client, readSelector, 0));
      }
      readSelector.wakeup();
      writeSelector.wakeup();
    } else if (client.isClosed()) {
      clients.remove(client.getChannel());
    }
  }

  /**
   * Runnable for the Acceptor thread.  This runs the acceptSelector on the AcceptorThread. 
   */
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
                doServerAccept(servers.get(server));
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

  /**
   * Runnable for the Read thread.  This runs the readSelector on the ReadThread. 
   */
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
              Client client = clients.get(sk.channel());
              if(sk.isConnectable()) {
                doClientConnect(client, readSelector);
                setClientOperations(client);
              } else {
                stats.addRead(doClientRead(client, readSelector));
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

  /**
   * Runnable for the Write thread.  This runs the writeSelector on the WriteThread. 
   */
  private class WriteRunner implements Runnable {
    @Override
    public void run() {
      if(isRunning()) {
        try {
          writeSelector.selectedKeys().clear();
          writeSelector.select();
          if(isRunning() && ! writeSelector.selectedKeys().isEmpty()) {
            for(SelectionKey sk: writeSelector.selectedKeys()) {
              final Client client = clients.get(sk.channel());
              stats.addWrite(doClientWrite(client, writeSelector));
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

  @Override
  public Executor getExecutorFor(Object obj) {
    return clientDistributer.getSubmitterForKey(obj);
  }
}
