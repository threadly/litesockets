package org.threadly.litesockets;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.util.Pair;


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

  public static enum UDPFilterMode {WhiteList, BlackList};

  private final ConcurrentHashMap<InetSocketAddress, UDPClient> clients = new ConcurrentHashMap<InetSocketAddress, UDPClient>();
  private final ConcurrentLinkedQueue<Pair<InetSocketAddress, ByteBuffer>> writeQueue = new ConcurrentLinkedQueue<Pair<InetSocketAddress, ByteBuffer>>();
  private final ConcurrentLinkedQueue<SettableListenableFuture<Long>> writeFutures = new ConcurrentLinkedQueue<SettableListenableFuture<Long>>();
  private final ConcurrentHashMap<InetAddress, Integer> filter = new ConcurrentHashMap<InetAddress, Integer>();
  private final DatagramChannel channel;
  private volatile UDPFilterMode filterMode = UDPFilterMode.BlackList;
  private volatile UDPReader setUDPReader = null;
  private volatile int frameSize = DEFAULT_FRAME_SIZE;
  private volatile ClientAcceptor clientAcceptor;

  protected UDPServer(final SocketExecuter sei, final String host, final int port) throws IOException {
    super(sei);
    channel = DatagramChannel.open();
    channel.socket().bind(new InetSocketAddress(host, port));
    channel.configureBlocking(false);
  }

  @Override
  public void start() {
    getSocketExecuter().setUDPServerOperations(this, true);
  }

  @Override
  public void stop() {
    getSocketExecuter().setUDPServerOperations(this, false);
  }

  /**
   * Sets the UDPfilterMode for the server.  This allows us to white or black list IP/ports from being accepted.
   * 
   * NOTE: calling set on this also resets any hosts currently already in the filter!
   * 
   * @param fm the UDPFilterMode to use.
   */
  public void setFilterMode(UDPFilterMode fm) {
    filterMode = fm;
    filter.clear();
  }

  /**
   * Adds a host to the filter.  How this filter will apply depends on what the UDPFilterMode is set to in the UDPServer.
   * A port number of 0 means we block/accept all ports for that host. 
   * 
   * @param isa the InetSocketAddress to use for the filter.
   */
  public void filterHost(InetSocketAddress isa) {
    filter.put(isa.getAddress(), isa.getPort());
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
        if(filterMode == UDPFilterMode.BlackList && filter.size() > 0) {
          Integer port = filter.get(isa.getAddress());
          if(port != null && (port == 0 || port == isa.getPort())) {
            return;
          }
        } else if (filterMode == UDPFilterMode.WhiteList) {
          Integer port = filter.get(isa.getAddress());
          if(port == null || (port != 0 && port != isa.getPort())) {
            return;
          }
        }
        bb.flip();
        getSocketExecuter().getExecutorFor(isa).execute(new NewDataRunnable(this, isa, bb));
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
    Pair<InetSocketAddress, ByteBuffer> wdp = writeQueue.poll();
    SettableListenableFuture<Long> slf = writeFutures.poll();
    if(wdp != null) {
      try {
        return channel.send(wdp.getRight(), wdp.getLeft());
      } catch (IOException e) {
        return 0;
      } finally {
        if(slf != null) {
          slf.setResult(0L);
        }
      }
    }
    return 0;
  }

  protected boolean needsWrite() {
    return !writeQueue.isEmpty();
  }

  /**
   * Allows you to write to the UDPServer directly without a UDPClient.
   * 
   * 
   * @param bb The {@link ByteBuffer} to write.
   * @param remoteAddress the remote host/port to write too.
   * @return a {@link ListenableFuture} that will be completed once the ByteBuffer for this write is put on the socket.
   */
  public ListenableFuture<?> write(final ByteBuffer bb, final InetSocketAddress remoteAddress) {
    SettableListenableFuture<Long> slf = new SettableListenableFuture<Long>();
    this.writeFutures.add(slf);
    writeQueue.add(new Pair<InetSocketAddress, ByteBuffer>(remoteAddress, bb));
    getSocketExecuter().setUDPServerOperations(this, true);
    return slf;
  }

  /**
   * Sets a {@link UDPReader} for this server.  This can be used to intercept reads before they create/call on a UDPClient.
   * 
   * Set to null to remove it.
   * 
   * @param udpReader the {@link UDPReader} to use for this UDPServer.
   */
  public void setUDPReader(final UDPReader udpReader) {
    this.setUDPReader = udpReader;
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
      UDPReader reader = us.setUDPReader;
      if(reader == null || reader.onUDPRead(bb.duplicate(), isa)) {
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

  }

  /**
   * The {@link UDPServer} UDPReader.  If a UDPReader is set on a UDPServer every read from the socket
   * will call onUDPRead before being passed to a UDPClient.  If false is returned the UDPPacket will not
   * be sent to the UDPClient, if true, then it will be.
   * 
   * @author lwahlmeier
   *
   */
  public interface UDPReader {
    
    /**
     * This is called whenever the UDPServer reads data from the socket.
     * 
     * @param bb the ByteBuffer containing the data from the read.
     * @param isa the SocketAddress of who sent the data.
     * @return true if the data should be passed onto the UDPClient, false if it should not be.
     */
    public boolean onUDPRead(ByteBuffer bb, InetSocketAddress isa);
  }
}
