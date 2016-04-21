package org.threadly.litesockets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * A Simple UDP Server. 
 * 
 * This is a UDP socket implementation for litesockets.  This UDPServer is treated like a
 * TCPServer.  It will notify the ClientAcceptor any time a new unique ip:port send a packet to this
 * UDP socket.  The UDPServer does not technically "Accept" new connections it just reads data from the socket
 * and that data also has the host/port of where it came from.
 * 
 * You can also just create a {@link UDPClient} from a server to initiate a connection to another UDP server, if
 * that server sends data back from that same port/ip pair it will show up as a read in the created client.
 */
public class UDPServer extends Server {
  public static final int DEFAULT_FRAME_SIZE = 1500;
  
  private final ConcurrentHashMap<InetSocketAddress, UDPClient> clients = new ConcurrentHashMap<InetSocketAddress, UDPClient>();
  private final ConcurrentLinkedQueue<WriteDataPair> writeQueue = new ConcurrentLinkedQueue<WriteDataPair>();
  private final DatagramChannel channel;
  private volatile int frameSize = DEFAULT_FRAME_SIZE;
  private volatile ClientAcceptor clientAcceptor;
  
  protected UDPServer(final SocketExecuter sei, final String host, final int port) throws IOException {
    super(sei);
    channel = DatagramChannel.open();
    channel.socket().bind(new InetSocketAddress(host, port));
    channel.configureBlocking(false);
  }
  
  protected void setFrameSize(final int size) {
    frameSize = size;
  }
  
  protected int getFrameSize() {
    return frameSize;
  }

  @Override
  public void acceptChannel(final SelectableChannel c) {
    if(c.equals(channel)) {
      final ByteBuffer bb = ByteBuffer.allocate(frameSize);
      try {
        final InetSocketAddress isa = (InetSocketAddress)channel.receive(bb);
        bb.flip();
        System.out.println("READ:"+bb);
        getSocketExecuter().getThreadScheduler().execute(new NewDataRunnable(this, isa, bb));
      } catch (IOException e) {

      }
    }
  }

  @Override
  public WireProtocol getServerType() {
    return WireProtocol.UDP;
  }

  @Override
  public DatagramChannel getSelectableChannel() {
    return channel;
  }

  @Override
  public ClientAcceptor getClientAcceptor() {
    return clientAcceptor;
  }

  @Override
  public void setClientAcceptor(final ClientAcceptor clientAcceptor) {
    this.clientAcceptor = clientAcceptor;
  }

  @Override
  public void close() {
    if(setClosed()) {
      try {
        getSocketExecuter().stopListening(this);
        channel.close();
      } catch (IOException e) {
        //Dont Care
      } finally {
        this.callClosers();
      }
    }
  }
  
  protected int doWrite() {
    WriteDataPair wdp = writeQueue.poll();
    if(wdp != null) {
      try {
        return channel.send(wdp.bb, wdp.isa);
      } catch (IOException e) {
        return 0;
      }
    }
    return 0;
  }
  
  protected boolean needsWrite() {
    return !writeQueue.isEmpty();
  }
  
  protected void write(ByteBuffer bb, InetSocketAddress remoteAddress) throws IOException {
    writeQueue.add(new WriteDataPair(remoteAddress, bb));
    getSocketExecuter().startListening(this);
  }
  
  /**
   * Creates a new client from this UDPServer.  If a client is already created for that
   * source address that client will be returned.
   * 
   * @param host the remote host to send data to.
   * @param port the port on that host to send data to.
   * @return a {@link UDPClient} pointing to that remote address. 
   */
  public UDPClient createUDPClient(final String host, final int port) {
    final InetSocketAddress sa = new InetSocketAddress(host,port);
    if(! clients.containsKey(sa)) {
      final UDPClient c = new UDPClient(new InetSocketAddress(host, port), this);
      clients.putIfAbsent(sa, c);
    }
    return clients.get(sa);
  }
  
  /**
   * Internal class used to deal with udpData, either creating a client for it or
   * adding to an existing client.
   * @author lwahlmeier
   *
   */
  private static class NewDataRunnable implements Runnable {
    private final InetSocketAddress isa;
    private final ByteBuffer bb;
    private final UDPServer us;
    
    public NewDataRunnable(final UDPServer us, final InetSocketAddress isa, final ByteBuffer bb) {
      this.us = us;
      this.isa = isa;
      this.bb = bb;
    }

    @Override
    public void run() {
      if(! us.clients.containsKey(isa)) {
        UDPClient udpc = new UDPClient(isa, us);
        udpc = us.clients.putIfAbsent(isa, udpc);
        if(udpc == null) {
          udpc = us.clients.get(isa);
          us.clientAcceptor.accept(udpc);
        }
      }
      final UDPClient udpc = us.clients.get(isa);
      if(udpc.canRead()) {
        udpc.addReadBuffer(bb);
      }      
    }
    
  }
  
  private static class WriteDataPair {
    private final ByteBuffer bb;
    private final InetSocketAddress isa;
    
    private WriteDataPair(InetSocketAddress isa, ByteBuffer bb) {
      this.bb = bb;
      this.isa = isa;
    }
  }

}
