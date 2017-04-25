package org.threadly.litesockets;

import java.nio.channels.CancelledKeyException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.wrapper.KeyDistributedExecutor;
import org.threadly.concurrent.wrapper.compatibility.ScheduledExecutorServiceWrapper;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.ExceptionUtils;

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

  private volatile long readThreadID;

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
  public ThreadedSocketExecuter(final ScheduledExecutorService exec) {
    this(new ScheduledExecutorServiceWrapper(exec));
  }

  /**
   * <p>Here you can provide a {@link ScheduledExecutorService} for this {@link SocketExecuter}.  This will be used
   * on accept, read, and close callback events.</p>
   * 
   * @param exec the {@link ScheduledExecutorService} to be used for client/server callbacks.
   */
  public ThreadedSocketExecuter(final SubmitterScheduler exec) {
    this(exec, Integer.MAX_VALUE);
  }
  
  public ThreadedSocketExecuter(final SubmitterScheduler exec, int maxTasksPerCycle) {
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
    super.startIfNotStarted();

    acceptSelector = openSelector();
    readSelector = openSelector();
    writeSelector = openSelector();
    acceptScheduler.execute(acceptor);
    readScheduler.execute(reader);
    writeScheduler.execute(writer);
  }

  @Override
  protected void shutdownService() {
    for(final Client client: clients.values()) {
      IOUtils.closeQuitly(client);
    }
    for(final Server server: servers.values()) {
      IOUtils.closeQuitly(server);
    }
    closeSelector(localAcceptScheduler, acceptSelector);
    closeSelector(localReadScheduler, readSelector);
    closeSelector(localWriteScheduler, writeSelector);
  }  

  @Override
  public void setClientOperations(final Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(!clients.containsKey(client.getChannel())) {
      clients.remove(client.getChannel());
      return;
    }

    synchronized(client) {
      if(client.isClosed()) {
        clients.remove(client.getChannel());
        ListenableFuture<?> lf = readScheduler.submit(new RemoveFromSelector(readSelector, client));
        ListenableFuture<?> lf2 = writeScheduler.submit(new RemoveFromSelector(writeSelector, client));
        FutureUtils.makeCompleteFuture(Arrays.asList(lf, lf2)).addListener(new Runnable() {
          @Override
          public void run() {
            IOUtils.closeQuitly(client.getChannel());
          }});
      } else if(!client.getChannel().isConnected() && client.getChannel().isConnectionPending()) {
        readScheduler.execute(new AddToSelector(readScheduler, client, readSelector, SelectionKey.OP_CONNECT));
        writeScheduler.execute(new AddToSelector(writeScheduler, client, writeSelector, 0));
      } else if(client.canWrite() && client.canRead()) {
        writeScheduler.execute(new AddToSelector(writeScheduler, client, writeSelector, SelectionKey.OP_WRITE));
        readScheduler.execute(new AddToSelector(readScheduler, client, readSelector, SelectionKey.OP_READ));
      } else if (client.canRead()){
        readScheduler.execute(new AddToSelector(readScheduler, client, readSelector, SelectionKey.OP_READ));
        writeScheduler.execute(new AddToSelector(writeScheduler, client, writeSelector, 0));
      } else if (client.canWrite()){
        writeScheduler.execute(new AddToSelector(writeScheduler, client, writeSelector, SelectionKey.OP_WRITE));
        readScheduler.execute(new AddToSelector(readScheduler, client, readSelector, 0));
      } else {
        writeScheduler.execute(new AddToSelector(writeScheduler, client, writeSelector, 0));
        readScheduler.execute(new AddToSelector(readScheduler, client, readSelector, 0));
      }
    }
    readSelector.wakeup();
    writeSelector.wakeup();
  }

  @Override
  public void setUDPServerOperations(final UDPServer udpServer, final boolean enable) {
    if(checkServer(udpServer)) {
      if(enable) {
        readScheduler.execute(new AddToSelector(readScheduler, udpServer, readSelector, SelectionKey.OP_READ));
        readSelector.wakeup();
        if(udpServer.needsWrite()) {
          writeScheduler.execute(new AddToSelector(writeScheduler, udpServer, writeSelector, SelectionKey.OP_WRITE));
          writeSelector.wakeup();
        } else {
          writeScheduler.execute(new AddToSelector(writeScheduler, udpServer, writeSelector, 0));
          writeSelector.wakeup();
        }
      } else {
        readScheduler.execute(new AddToSelector(readScheduler, udpServer, readSelector, 0));
        writeScheduler.execute(new AddToSelector(writeScheduler, udpServer, writeSelector, 0));
        readSelector.wakeup();
        writeSelector.wakeup();
      }
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
        } catch (Exception e) {
          ExceptionUtils.handleException(e);
          stopIfRunning();
          return;
        } 
        if(isRunning()) {
          for(final SelectionKey sk: acceptSelector.selectedKeys()) {
            if(sk.isAcceptable()) {
              final ServerSocketChannel server = (ServerSocketChannel) sk.channel();
              doServerAccept(servers.get(server));
            }
          }
        }
        if(isRunning()) {
          acceptScheduler.execute(this);
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
        } catch (Exception e) {
          ExceptionUtils.handleException(e);
          stopIfRunning();
          return;
        }
        if(isRunning() && ! readSelector.selectedKeys().isEmpty()) {
          for(final SelectionKey sk: readSelector.selectedKeys()) {
            final Client client = clients.get(sk.channel());
            if(client != null) {
              try {
                if(sk.isConnectable()) {
                  doClientConnect(client, readSelector);
                  sk.cancel();
                  setClientOperations(client);
                } else {
                  doClientRead(client, readSelector);
                }
              } catch(CancelledKeyException e) {
                IOUtils.closeQuitly(client);
                ExceptionUtils.handleException(e);
              }
            } else {
              final Server server = servers.get(sk.channel());
              if(server != null) {
                if(sk.isReadable()) {
                  final DatagramChannel dgc = (DatagramChannel) sk.channel();
                  server.acceptChannel(dgc);
                }
              }
            }
          }
        }
        if(isRunning()) {
          readScheduler.execute(this);
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
        } catch (Exception e) {
          ExceptionUtils.handleException(e);
          stopIfRunning();
          return;
        }
        if(isRunning() && ! writeSelector.selectedKeys().isEmpty()) {
          for(final SelectionKey sk: writeSelector.selectedKeys()) {
            final Client client = clients.get(sk.channel());
            if(client != null) {
              doClientWrite(client, writeSelector);
            } else {
              final Server server = servers.get(sk.channel());
              if(server != null) {
                if(server instanceof UDPServer) {
                  UDPServer us = (UDPServer) server;
                  us.doWrite();
                  setUDPServerOperations(us, true);
                }
              }
            }
          }
        }
        if(isRunning()) {
          writeScheduler.execute(this);
        }
      }
    }
  }

  @Override
  public SubmitterExecutor getExecutorFor(final Object obj) {
    return clientDistributer.getExecutorForKey(obj);
  }
}
