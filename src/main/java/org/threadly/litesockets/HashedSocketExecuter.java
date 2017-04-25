package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.wrapper.KeyDistributedExecutor;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;

public class HashedSocketExecuter extends SocketExecuterCommonBase {
  private final ConcurrentHashMap<Integer, SelectorThread> clientSelectors = new ConcurrentHashMap<>();
  private final KeyDistributedExecutor clientDistributer;
  private final int selectors;
  
  public HashedSocketExecuter(SubmitterScheduler scheduler) {
    this(scheduler, Integer.MAX_VALUE, 5);
  }

  public HashedSocketExecuter(SubmitterScheduler scheduler, int maxTasksPerCycle, int numberOfSelectors) {
    super(scheduler);
    clientDistributer = new KeyDistributedExecutor(schedulerPool, maxTasksPerCycle);
    this.selectors = numberOfSelectors;
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
    final SelectorThread st = clientSelectors.get(client.hashCode()%clientSelectors.size());
    synchronized(client) {
      
      if(client.isClosed()) {
        clients.remove(client.getChannel());
        st.scheduler.execute(new RemoveFromSelector(st.selector, client));
        st.scheduler.execute(()->IOUtils.closeQuietly(client.getChannel()));
      } else if(!client.getChannel().isConnected() && client.getChannel().isConnectionPending()) {
        st.scheduler.execute(new AddToSelector(st.scheduler,client, st.selector, SelectionKey.OP_CONNECT));
      } else if(client.canWrite() && client.canRead()) {
        st.scheduler.execute(new AddToSelector(st.scheduler,client, st.selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE));
      } else if (client.canRead()){
        st.scheduler.execute(new AddToSelector(st.scheduler,client, st.selector, SelectionKey.OP_READ));
      } else if (client.canWrite()){
        st.scheduler.execute(new AddToSelector(st.scheduler,client, st.selector, SelectionKey.OP_WRITE));
      } else {
        st.scheduler.execute(new AddToSelector(st.scheduler,client, st.selector, 0));
      }
      st.selector.wakeup();
    }
  }
  
  
  @Override
  public void startListening(final Server server) {
    if(!checkServer(server)) {
      return;
    } else {
      final SelectorThread st = clientSelectors.get(server.hashCode()%clientSelectors.size());
      if(server.getServerType() == WireProtocol.TCP) {
        st.scheduler.execute(new AddToSelector(st.scheduler, server, st.selector, SelectionKey.OP_ACCEPT));
        st.selector.wakeup();
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
      final SelectorThread st = clientSelectors.get(server.hashCode()%clientSelectors.size());
      if(server.getServerType() == WireProtocol.TCP) {
        st.scheduler.execute(new AddToSelector(st.scheduler, server, st.selector, 0));
        st.selector.wakeup();
      } else if(server.getServerType() == WireProtocol.UDP) {
        st.scheduler.execute(new AddToSelector(st.scheduler, server, st.selector, 0));
        st.selector.wakeup();
      } else {
        throw new UnsupportedOperationException("Unknown Server WireProtocol!"+ server.getServerType());
      }
    }
  }

  @Override
  public void setUDPServerOperations(UDPServer udpServer, boolean enable) {
    if(checkServer(udpServer)) {
      final SelectorThread st = clientSelectors.get(udpServer.hashCode()%clientSelectors.size());
      if(enable) {
        if(udpServer.needsWrite()) {
          st.scheduler.execute(new AddToSelector(st.scheduler, udpServer, st.selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE));
        } else {
          st.scheduler.execute(new AddToSelector(st.scheduler, udpServer, st.selector, SelectionKey.OP_READ));  
        }
      } else {
        st.scheduler.execute(new AddToSelector(st.scheduler, udpServer, st.selector, 0));
      }
      st.selector.wakeup();
    }
  }

  @Override
  protected void startupService() {
    for(int i=0; i<selectors; i++) {
      clientSelectors.put(i, new SelectorThread(i));
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
    for(SelectorThread st: clientSelectors.values()) {
      closeSelector(st.scheduler, st.selector);
      st.selector.wakeup();
    }
  }
  
  private class SelectorThread {
    private final int id;
    private final Selector selector;
    private final SingleThreadScheduler scheduler;
    
    public SelectorThread(int id) {
      this.id = id;
      selector = openSelector();
      scheduler = new SingleThreadScheduler(new ConfigurableThreadFactory("SelectorThread-"+id, false, true, Thread.currentThread().getPriority(), null, null));
      scheduler.execute(()->doSelect(selector, scheduler, HashedSocketExecuter.this));
    }
  }
  
  private static void doSelect(final Selector selector, final Executor exec, final SocketExecuterCommonBase seb) {
      try {
        selector.select();
        for(final SelectionKey key: selector.selectedKeys()) {
          try {
            if(key.isAcceptable()) {
              seb.doServerAccept(seb.servers.get(key.channel()));
            } else {
              final Client tmpClient = seb.clients.get(key.channel());
              if(key.isConnectable() && tmpClient != null) {
                seb.doClientConnect(tmpClient, selector);
                key.cancel(); //Stupid windows bug here.
                seb.setClientOperations(tmpClient);
              } else {
                if (key.isReadable()) {
                  if(tmpClient != null){
                    seb.doClientRead(tmpClient, selector);
                  } else {
                    final Server server = seb.servers.get(key.channel());
                    if(server != null && server.getServerType() == WireProtocol.UDP) {
                      server.acceptChannel((DatagramChannel)server.getSelectableChannel());
                    }
                  }
                } 
                if(key.isWritable()) {
                  if(tmpClient != null){
                    seb.doClientWrite(tmpClient, selector);
                  } else {
                    final Server server = seb.servers.get(key.channel());
                    if(server != null) {
                      if(server instanceof UDPServer) {
                        UDPServer us = (UDPServer) server;
                        seb.stats.addWrite(us.doWrite());
                        seb.setUDPServerOperations(us, true);
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
    if(seb.isRunning()) {
      exec.execute(()->doSelect(selector, exec, seb));
    }
  }

}
