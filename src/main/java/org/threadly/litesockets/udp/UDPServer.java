package org.threadly.litesockets.udp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.KeyDistributedScheduler;
import org.threadly.concurrent.SchedulerServiceInterface;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.SocketExecuterBase.WireProtocol;

public class UDPServer extends Server {
  
  protected final DatagramChannel channel;
  private volatile ClientAcceptor clientAcceptor;
  private KeyDistributedScheduler clientDistributer;
  private final ConcurrentHashMap<SocketAddress, UDPClient> clients = new ConcurrentHashMap<SocketAddress, UDPClient>();
  
  
  public UDPServer(String host, int port) throws IOException {
    channel = DatagramChannel.open();
    channel.bind(new InetSocketAddress(host, port));
    channel.configureBlocking(false);
  }
  
  @Override
  protected void setThreadExecuter(SchedulerServiceInterface sei) {
    super.setThreadExecuter(sei);
    clientDistributer = new KeyDistributedScheduler(sei);
  }

  //This needs to be done before we select again
  @Override
  protected void callAcceptor(SelectableChannel c) {
    accept(c);
  }
  
  @Override
  public void accept(SelectableChannel c) {
    if(c == channel) {
      final ByteBuffer bb = ByteBuffer.allocate(1500);
      try {
        final SocketAddress sa = channel.receive(bb);
        bb.flip();
        sei.execute(new Runnable() {
          @Override
          public void run() {
            if(! clients.containsKey(sa)) {
              UDPClient udpc = new UDPClient(sa, UDPServer.this);
              udpc = clients.putIfAbsent(sa, udpc);
              if(udpc == null) {
                udpc = clients.get(sa);
                udpc.setThreadExecuter(clientDistributer.getSubmitterForKey(udpc));
                clientAcceptor.accept(udpc);
              }
            }
            UDPClient udpc = clients.get(sa);
            udpc.addReadBuffer(bb);
            udpc.callReader();
          }});
      } catch (IOException e) {

      }
    }
  }

  @Override
  public WireProtocol getServerType() {
    return WireProtocol.UDP;
  }

  @Override
  public SelectableChannel getSelectableChannel() {
    return channel;
  }

  @Override
  public ClientAcceptor getClientAcceptor() {
    return clientAcceptor;
  }

  @Override
  public void setClientAcceptor(ClientAcceptor clientAcceptor) {
    this.clientAcceptor = clientAcceptor;
  }

  @Override
  public void close() {
    try {
      channel.close();
    } catch (IOException e) {
      //Dont Care
    } finally {
      this.callCloser();
    }
  }
  
  public UDPClient createUDPClient(String host, int port) {
    InetSocketAddress sa = new InetSocketAddress(host,port);
    if(! clients.containsKey(sa)) {
      UDPClient c = new UDPClient(new InetSocketAddress(host, port), this);
      clients.putIfAbsent(sa, c);
      c.setThreadExecuter(clientDistributer.getSubmitterForKey(c));
    }
    return clients.get(sa);
  }

}
