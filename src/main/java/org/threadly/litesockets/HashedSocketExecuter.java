package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.threadly.concurrent.ConfigurableThreadFactory;
import org.threadly.concurrent.SingleThreadScheduler;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.wrapper.KeyDistributedExecutor;
import org.threadly.litesockets.utils.IOUtils;
import org.threadly.util.ArgumentVerifier;

public class HashedSocketExecuter extends SocketExecuterCommonBase {
  private final ArrayList<SelectorThread> clientSelectors;
  private final KeyDistributedExecutor clientDistributer;
  private final int selectors;
  
  public HashedSocketExecuter(SubmitterScheduler scheduler) {
    this(scheduler, Integer.MAX_VALUE, 5);
  }

  public HashedSocketExecuter(SubmitterScheduler scheduler, int maxTasksPerCycle, int numberOfSelectors) {
    super(scheduler);
    clientSelectors = new ArrayList<>(numberOfSelectors);
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
    final SelectorThread st = clientSelectors.get(client.hashCode()%selectors);
    st.addClient(client);
  }
  
  @Override
  public void startListening(final Server server) {
    if(checkServer(server)) {
      final SelectorThread st = clientSelectors.get(server.hashCode()%selectors);
      st.addServer(server);
    }
  }

  @Override
  public void stopListening(final Server server) {
    if(checkServer(server)) {
      final SelectorThread st = clientSelectors.get(server.hashCode()%selectors);
      st.removeServer(server);
    }
  }

  @Override
  public void setUDPServerOperations(UDPServer udpServer, boolean enable) {
    if(checkServer(udpServer)) {
      final SelectorThread st = clientSelectors.get(udpServer.hashCode()%selectors);
      if(enable) {
        st.addServer(udpServer);
      } else {
        
      }st.removeServer(udpServer);
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
      st.selector.wakeup();
      st.selector.wakeup();
      
      IOUtils.closeQuietly(st.selector);
    }
  }
  
  private class SelectorThread {
    private final Selector selector;
    private final Thread thread;
    private final ConcurrentLinkedQueue<Client> clientsToCheck = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Server> serversToAdd = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Server> serversToRemove = new ConcurrentLinkedQueue<>();
    private volatile boolean isAwake = true;
    
    public SelectorThread(int id) {
      selector = openSelector();
      //scheduler = new SingleThreadScheduler(new ConfigurableThreadFactory("SelectorThread-"+id, false, true, Thread.currentThread().getPriority(), null, null));
      thread = new Thread(()->doSelect());
      thread.setDaemon(true);
      thread.start();
    }
    
    public void addClient(Client client) {
      clientsToCheck.add(client);
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    public void addServer(Server server) {
      serversToAdd.add(server);
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    public void removeServer(Server server) {
      serversToAdd.add(server);
      if(!isAwake) {
        isAwake = true;
        selector.wakeup();
      }
    }
    
    private void processServers() {
      Server server = serversToAdd.poll();
      while(server != null) {
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
        server = serversToAdd.poll();  
      }
      server = serversToRemove.poll();
      while(server != null) {
        SelectionKey sk = server.getSelectableChannel().keyFor(selector);
        if(sk != null) {
          sk.cancel();
        }
        server = serversToRemove.poll();
      }
    }
    
    private void processClients() {
      Client client = clientsToCheck.poll();
      while(client != null) {
        final Client fc = client;
        try {
          SelectionKey sk = client.getChannel().keyFor(selector);
          if(client.isClosed()) {
            clients.remove(client.getChannel());
            if(sk != null) {
              sk.cancel();
            }
            client.getClientsThreadExecutor().execute(()->IOUtils.closeQuietly(fc.getChannel()));
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
          e.printStackTrace();
          IOUtils.closeQuietly(client);
        }
        client = clientsToCheck.poll();
      }
    }


    private void doSelect() {
      while(isRunning()) {
      try {
        isAwake = false;
        processClients();
        processServers();
        selector.selectedKeys().clear();
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


      }
    }
  }
}
