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

import org.threadly.concurrent.KeyDistributedScheduler;
import org.threadly.concurrent.SchedulerServiceInterface;
import org.threadly.concurrent.SingleThreadScheduler;

public class ThreadedSocketExecuter extends SocketExecuterBase {
  private final SingleThreadScheduler acceptScheduler = new SingleThreadScheduler();
  private final SingleThreadScheduler readScheduler = new SingleThreadScheduler();
  private final SingleThreadScheduler writeScheduler = new SingleThreadScheduler();
  private final KeyDistributedScheduler clientDistributer;
  private final SchedulerServiceInterface schedulerPool;
  private final ConcurrentHashMap<SocketChannel, Client> clients = new ConcurrentHashMap<SocketChannel, Client>();
  private final ConcurrentHashMap<SelectableChannel, Server> servers = new ConcurrentHashMap<SelectableChannel, Server>();

  private volatile long readThreadID = 0;

  protected Selector readSelector;
  protected Selector writeSelector;
  protected Selector acceptSelector;

  private AcceptRunner acceptor;
  private ReadRunner reader;
  private WriteRunner writer;


  public ThreadedSocketExecuter() {
    schedulerPool = new SingleThreadScheduler();
    clientDistributer = new KeyDistributedScheduler(schedulerPool);
  }

  public ThreadedSocketExecuter(SchedulerServiceInterface ssi) {
    schedulerPool = ssi;
    clientDistributer = new KeyDistributedScheduler(schedulerPool);
  }

  @Override
  public void addClient(final Client client) {
    if(! client.isClosed() && client.getChannel() != null && isRunning()) {
      client.setThreadExecuter(clientDistributer.getSubmitterForKey(client));
      client.setSocketExecuter(this);
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
    }
  }

  @Override
  public void removeClient(Client client) {
    if(isRunning()) {
      Client c = clients.remove(client.getChannel());
      if(c != null) {
        SelectionKey sk = client.getChannel().keyFor(readSelector);
        SelectionKey sk2 = client.getChannel().keyFor(writeSelector);
        if(sk != null) {
          sk.cancel();
        }
        if(sk2 != null) {
          sk2.cancel();
        }
      }
    }
  }

  @Override
  public void addServer(final Server server) {
    if(isRunning()) {
      Server sn = servers.putIfAbsent(server.getSelectableChannel(), server);
      if(sn == null) {
        server.setServerExecuter(this);
        server.setThreadExecuter(schedulerPool);
        acceptScheduler.execute(new Runnable() {
          @Override
          public void run() {
            SelectionKey key = server.getSelectableChannel().keyFor(acceptSelector);
            if(key == null) {
              try {
                if(server.getServerType() == Server.ServerProtocol.TCP) {
                  server.getSelectableChannel().register(acceptSelector, SelectionKey.OP_ACCEPT);
                } else if(server.getServerType() == Server.ServerProtocol.UDP) {
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
    if(isRunning()) {
      servers.remove(server.getSelectableChannel());
      acceptSelector.wakeup();
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

  }

  @Override
  protected void flagNewWrite(Client client) {
    if(isRunning() && clients.containsKey(client.getChannel())) {
      writeScheduler.execute(new AddToSelector(client, writeSelector, SelectionKey.OP_WRITE));
      writeSelector.wakeup();
    }
  }

  @Override
  protected void flagNewRead(Client client) {
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


  @Override
  protected boolean verifyReadThread() {
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
                      tServer.callAcceptor(client);
                    }
                  } catch (IOException e) {
                    removeServer(tServer);
                    tServer.close();
                  }
                } else if(sk.isReadable()) {
                  DatagramChannel server = (DatagramChannel) sk.channel();
                  final Server udpServer = servers.get(server);
                  udpServer.callAcceptor(server);
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
              if(client != null) {
                try {
                  ByteBuffer readByteBuffer = client.getBuffer();
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
                    client.callReader();
                    if(! client.canRead()) {
                      client.getChannel().register(readSelector, 0);
                    }
                  }
                } catch(IOException e) {
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
                } catch(IOException e) {
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
  public SchedulerServiceInterface getThreadScheduler() {
    return schedulerPool;
  }
}
