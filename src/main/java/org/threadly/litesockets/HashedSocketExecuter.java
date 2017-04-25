package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.wrapper.KeyDistributedExecutor;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;

public class HashedSocketExecuter extends SocketExecuterCommonBase {
  private final ArrayList<SelectorThread> clientSelectors = new ArrayList<>();
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
    st.addClient(client);
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
      clientSelectors.add(new SelectorThread(i));
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
      closeSelector(st.scheduler, st.selector);
      st.selector.wakeup();
    }
  }
  
  private class SelectorThread {
    private final int id;
    private final Selector selector;
    private final SingleThreadScheduler scheduler;
    private final ConcurrentLinkedQueue<Client> clientsToCheck = new ConcurrentLinkedQueue<>();
    private volatile boolean isAwake = true;
    
    public SelectorThread(int id) {
      this.id = id;
      selector = openSelector();
      scheduler = new SingleThreadScheduler(new ConfigurableThreadFactory("SelectorThread-"+id, false, true, Thread.currentThread().getPriority(), null, null));
      scheduler.execute(()->doSelect());
    }
    
    public void addClient(Client client) {
      clientsToCheck.add(client);
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    private void processClients() {
      Client client = clientsToCheck.poll();
      while(client != null) {
        final Client fc = client;
        try {
          if(client.isClosed()) {
            clients.remove(client.getChannel());
            SelectionKey sk = client.getChannel().keyFor(selector);
            if(sk != null) {
              sk.cancel();
            }
            client.getClientsThreadExecutor().execute(()->IOUtils.closeQuietly(fc.getChannel()));
          } else if(!client.getChannel().isConnected() && client.getChannel().isConnectionPending()) {
            client.getChannel().register(selector, SelectionKey.OP_CONNECT);
          } else if(client.canWrite() && client.canRead()) {
            client.getChannel().register(selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE);
          } else if (client.canRead()){
            client.getChannel().register(selector, SelectionKey.OP_READ);
          } else if (client.canWrite()){
            client.getChannel().register(selector, SelectionKey.OP_WRITE);
          } else {
            client.getChannel().register(selector, 0);
          }
        } catch (CancelledKeyException e) {
          scheduler.execute(()->addClient(fc));
        } catch (ClosedChannelException e) {
          IOUtils.closeQuietly(client);
        }
        client = clientsToCheck.poll();
      }
    }


    private void doSelect() {
      try {
        isAwake = false;
        processClients();
        selector.select();
        isAwake = true;
        for(final SelectionKey key: selector.selectedKeys()) {
          try {
            if(key.isAcceptable()) {
              doServerAccept(servers.get(key.channel()));
            } else {
              final Client tmpClient = clients.get(key.channel());
              if(key.isConnectable() && tmpClient != null) {
                doClientConnect(tmpClient, selector);
                key.cancel(); //Stupid windows bug here.
                setClientOperations(tmpClient);
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
                        stats.addWrite(us.doWrite());
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
      if(isRunning()) {
        scheduler.execute(()->doSelect());
      }
    }
  }

}
