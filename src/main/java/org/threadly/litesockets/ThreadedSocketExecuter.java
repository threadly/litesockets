package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.wrapper.KeyDistributedExecutor;
import org.threadly.concurrent.wrapper.compatibility.ScheduledExecutorServiceWrapper;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.ExceptionUtils;


/**
 * This is a multiThreaded Implementation of a SocketExecuter.  It runs multipule
 * Selectors on different threads to help reduce select loop exhaustion. 
 * 
 */
public class ThreadedSocketExecuter extends SocketExecuterCommonBase {
  private final SelectorThread[] clientSelectors;
  private final KeyDistributedExecutor clientDistributer;
  
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
   * <p>Here you can provide a {@link SubmitterScheduler} for this {@link SocketExecuter}.  This will be used
   * on accept, read, and close callback events.</p>
   * 
   * @param scheduler the {@link SubmitterScheduler} to be used for client/server callbacks.
   */
  public ThreadedSocketExecuter(final SubmitterScheduler scheduler) {
    this(scheduler, Integer.MAX_VALUE, -1);
  }
  
  /**
   * <p>Creates a ThreadedSocketExecuter with different max task cycle.</p> 
   * 
   * @param scheduler the {@link SubmitterScheduler} to be used for client/server callbacks.
   * @param maxTasksPerCycle the max number of tasks to run on a clients thread before returning the thread back to the pool.
   */
  public ThreadedSocketExecuter(final SubmitterScheduler scheduler, final int maxTasksPerCycle) {
    this(scheduler, maxTasksPerCycle, -1);
  }
  
  /**
   * <p>Creates a ThreadedSocketExecuter with different max task cycle and Selector Thread defaults.</p> 
   * 
   * @param scheduler the {@link SubmitterScheduler} to be used for client/server callbacks.
   * @param maxTasksPerCycle the max number of tasks to run on a clients thread before returning the thread back to the pool.
   * @param numberOfSelectors the number of selector threads to run.  Default is core/2.
   */
  public ThreadedSocketExecuter(final SubmitterScheduler scheduler, final int maxTasksPerCycle, final int numberOfSelectors) {
    super(scheduler);
    
    int ps = -1;
    if(numberOfSelectors <= 0) {
      ps = Math.max(1,  Runtime.getRuntime().availableProcessors()/2);
    } else {
      ps = numberOfSelectors;
    }
    clientSelectors = new SelectorThread[ps];
    clientDistributer = new KeyDistributedExecutor(schedulerPool, maxTasksPerCycle);
  }
  
  private SelectorThread getSelectorFor(Object obj) {
    if(clientSelectors.length == 1) {
      return clientSelectors[0];
    } 
    return clientSelectors[obj.hashCode() % clientSelectors.length];
  }

  @Override
  public SubmitterExecutor getExecutorFor(Object obj) {
    return clientDistributer.getExecutorForKey(obj);
  }

  @Override
  public void setClientOperations(Client client) {
    ArgumentVerifier.assertNotNull(client, "Client");
    if(!clients.containsKey(client.getChannel())) {
      return;
    }
    final SelectorThread st = getSelectorFor(client);
    st.addClient(client);
  }
  
  @Override
  public void startListening(final Server server) {
    if(checkServer(server)) {
      final SelectorThread st = getSelectorFor(server);
      st.addServer(server);
    }
  }

  @Override
  public void stopListening(final Server server) {
    if(checkServer(server)) {
      final SelectorThread st = getSelectorFor(server);
      st.removeServer(server);
    }
  }

  @Override
  public void setUDPServerOperations(UDPServer udpServer, boolean enable) {
    if(checkServer(udpServer)) {
      final SelectorThread st = getSelectorFor(udpServer);
      if(enable) {
        st.addServer(udpServer);
      } else {
        st.removeServer(udpServer);
      }
    }
  }

  @Override
  protected void startupService() {
    for(int i=0; i < clientSelectors.length; i++) {
      clientSelectors[i] = new SelectorThread(i);
    }
  }

  @Override
  protected void shutdownService() {
    for(final Client client: clients.values()) {
      IOUtils.closeQuietly(client);
    }
    for(final Server server: servers.values()) {
      IOUtils.closeQuietly(server);
    }
    for(SelectorThread st: clientSelectors) {
      st.selector.wakeup();
      st.selector.wakeup();
      
      IOUtils.closeQuietly(st.selector);
    }
  }
  
  /**
   * 
   */
  private class SelectorThread {
    private final Selector selector;
    private final Thread thread;
    private final ConcurrentLinkedQueue<Runnable> processQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isAwake = true;
    
    public SelectorThread(int id) {
      selector = openSelector();
      thread = new Thread(()->doSelect(), "HashedSelector-"+id);
      thread.setDaemon(true);
      thread.start();
    }
    
    public void addClient(Client client) {
      processQueue.add(()->processClient(client));
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    public void addServer(Server server) {
      processQueue.add(()->processServerAdd(server));
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    public void removeServer(Server server) {
      processQueue.add(()->processServerRemove(server));
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    private void processServerAdd(final Server server) {
      try {
        if(server.getServerType() == WireProtocol.TCP) {
          server.getSelectableChannel().register(selector, SelectionKey.OP_ACCEPT);
        } else if(server.getServerType() == WireProtocol.UDP) {
          UDPServer us = (UDPServer) server;
          if(us.needsWrite()) {
            server.getSelectableChannel().register(selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE);  
          } else {
            server.getSelectableChannel().register(selector, SelectionKey.OP_READ);
          }
        }
      } catch(Exception e) {
        IOUtils.closeQuietly(server);
      }
    }
    
    private void processServerRemove(final Server server) {
      SelectionKey sk = server.getSelectableChannel().keyFor(selector);
      if(sk != null) {
        sk.cancel();
      }
    }
    
    private void processClient(final Client client) {
      final Client fc = client;
      try {
        SelectionKey sk = client.getChannel().keyFor(selector);
        if(client.isClosed()) {
          clients.remove(client.getChannel());
          if(sk != null) {
            sk.cancel();
          }
          if(client.getChannel().isOpen()) {
            client.getClientsThreadExecutor().execute(()->IOUtils.closeQuietly(fc.getChannel()));
          }
        } else {
          if(sk == null) {
            sk = client.getChannel().register(selector, 0);
          }
          if(!client.getChannel().isConnected() && client.getChannel().isConnectionPending()) {
            sk.interestOps(SelectionKey.OP_CONNECT);
          } else if(client.canWrite() && client.canRead()) {
            sk.interestOps(SelectionKey.OP_READ|SelectionKey.OP_WRITE);
          } else if (client.canRead()){
            sk.interestOps(SelectionKey.OP_READ);
          } else if (client.canWrite()){
            sk.interestOps(SelectionKey.OP_WRITE);
          } else {
            sk.interestOps(0);
          }
        }
      } catch (CancelledKeyException e) {
        addClient(fc);
      } catch (Exception e) {
        ExceptionUtils.handleException(e);
        IOUtils.closeQuietly(client);
      }
    }

    private void doSelect() {
      while(isRunning()) {
      try {
        isAwake = false;
        while(!processQueue.isEmpty()) {
          try {
            processQueue.poll().run();
          } catch(Exception e) {
            
          }
        }
        selector.selectedKeys().clear();
        selector.select();
        isAwake = true;
        for(final SelectionKey key: selector.selectedKeys()) {
          try {
            if(key.isAcceptable()) {
              key.interestOps(0);
              schedulerPool.execute(()->{
                Server s = servers.get(key.channel());
                doServerAccept(s);
                addServer(s);
              });
            } else {
              final Client tmpClient = clients.get(key.channel());
              if(key.isConnectable() && tmpClient != null) {
                key.cancel(); //Stupid windows bug here.
                doClientConnect(tmpClient, selector);
              } else {
                if (key.isReadable()) {
                  if(tmpClient != null){
                    doClientRead(tmpClient, selector);
                  } else {
                    final Server server = servers.get(key.channel());
                    if(server != null && server.getServerType() == WireProtocol.UDP) {
                      server.acceptChannel((DatagramChannel)server.getSelectableChannel());
                    }
                  }
                } 
                if(key.isWritable()) {
                  if(tmpClient != null){
                    doClientWrite(tmpClient, selector);
                  } else {
                    final Server server = servers.get(key.channel());
                    if(server != null) {
                      if(server instanceof UDPServer) {
                        UDPServer us = (UDPServer) server;
                        recordWriteStats(us.doWrite());
                        setUDPServerOperations(us, true);
                      }
                    }
                  }
                }
              }
            }
          } catch(CancelledKeyException e) {
            //Key could be cancelled at any point, we dont really care about it.
          }
        }
        //Also for windows bug, canceled keys are not removed till we select again.
        //So we just have to at the end of the loop.
        selector.selectNow();
      } catch (IOException e) {
        //There is really nothing to do here but try again, usually this is because of shutdown.
      } catch(ClosedSelectorException e) {
        //We do nothing here because the next loop should not happen now.
      } catch (NullPointerException e) {
        //There is a bug in some JVMs around this where the select() can throw an NPE from native code.
      }
      }
    }
  }
}
