package org.threadly.litesockets.udp;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterBase;
import org.threadly.litesockets.SocketExecuterBase.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.Clock;

public class UDPClient implements Client {
  public static final int DEFAULT_MAX_BUFFER_SIZE = 64*1024;
  public static final int MIN_READ= 4*1024;

  protected ClientByteStats stats = new ClientByteStats();

  protected final long startTime = Clock.lastKnownForwardProgressingMillis();
  private final MergedByteBuffers readBuffers = new MergedByteBuffers();
  private final MergedByteBuffers writeBuffers = new MergedByteBuffers();
  protected final SocketAddress sa;
  protected final UDPServer udpServer;

  protected int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;
  protected int minAllowedReadBuffer = MIN_READ;
  private ByteBuffer readByteBuffer = ByteBuffer.allocate(maxBufferSize*2);

  protected volatile Closer closer;
  protected volatile Reader reader;
  protected volatile SubmitterExecutorInterface sei;
  protected volatile SocketExecuterBase seb;
  protected AtomicBoolean closed = new AtomicBoolean(false);

  //protected final String host;
  //protected final int port;
  

  
  protected UDPClient(SocketAddress sa, UDPServer server) {
    this.sa = sa;
    udpServer = server;
  }
  
  @Override
  public boolean equals(Object o) {
    if(o instanceof UDPClient) {
      if(hashCode() == o.hashCode()) {
        UDPClient u = (UDPClient)o;
        if(u.sa.equals(this.sa) && u.udpServer.getSelectableChannel().equals(udpServer.getSelectableChannel())) {
          return true;
        }
      }
    }
    return false;
  }
  
  @Override
  public int hashCode() {
    return sa.hashCode() * udpServer.getSelectableChannel().hashCode();
  }
  
  @Override
  public void setClientsThreadExecutor(SubmitterExecutorInterface  sei) {
    if(sei != null) {
      this.sei = sei;
    }
  }

  @Override
  public SocketChannel getChannel() {
    return null;
  }

  @Override
  public Socket getSocket() {
    return null;
  }

  @Override
  public boolean isClosed() {
    return this.closed.get();
  }

  @Override
  public void close() {
    if(closed.compareAndSet(false, true)) {
      try {
        if(seb != null) {
          seb.removeClient(this);
        }
      } finally {
        final Closer lcloser = closer;
        if(lcloser != null && this.sei != null) {
          sei.execute(new Runnable() {
            @Override
            public void run() {
              lcloser.onClose(UDPClient.this);
            }});
        }
      }
    }

  }
  
  @Override
  public void addReadBuffer(ByteBuffer bb) {
    stats.addRead(bb.remaining());
    final Reader lreader = reader;
    if(lreader != null) {
      synchronized(readBuffers) {
        int start = readBuffers.remaining();
        readBuffers.add(bb);
        if(start == 0){
          sei.execute(new Runnable() {
            @Override
            public void run() {
              lreader.onRead(UDPClient.this);
            }});
        }
      }
    }
  }
  
  @Override
  public void writeForce(ByteBuffer bb) {
    stats.addWrite(bb.remaining());
    if(!closed.get()) {
      try {
        udpServer.channel.send(bb, sa);
      } catch (IOException e) {
      }
    }
  }
  
  @Override
  public boolean writeTry(ByteBuffer bb) {
    if(!this.closed.get()) {
      writeForce(bb);
      return true;
    }
    return false;
  }
  
  @Override
  public void writeBlocking(ByteBuffer bb) {
    writeForce(bb);
  }
  
  @Override
  public void setReader(Reader reader) {
    this.reader = reader;
  }
  
  @Override
  public Reader getReader() {
    return reader;
  }
  
  @Override
  public void setCloser(Closer closer) {
    this.closer = closer;
  }
  
  @Override
  public Closer getCloser() {
    return closer;
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.UDP;
  }
  
  private static class ClientByteStats extends SimpleByteStats {
    public ClientByteStats() {
      super();
    }

    @Override
    protected void addWrite(int size) {
      if(size < 0) {
        throw new IllegalArgumentException("Size must be positive number");
      }
      super.addWrite(size);
    }
    
    @Override
    protected void addRead(int size) {
      if(size < 0) {
        throw new IllegalArgumentException("Size must be positive number");
      }
      super.addRead(size);
    }
  }

  @Override
  public SimpleByteStats getStats() {
    return stats;
  }

  @Override
  public boolean canRead() {
    return true;
  }

  @Override
  public boolean canWrite() {
    return true;
  }

  @Override
  public ByteBuffer provideEmptyReadBuffer() {
    if(readByteBuffer.remaining() < minAllowedReadBuffer) {
      readByteBuffer = ByteBuffer.allocate(maxBufferSize*2);
    }
    return readByteBuffer;
  }

  @Override
  public int getReadBufferSize() {
    return this.readBuffers.remaining();
  }

  @Override
  public int getWriteBufferSize() {
    return this.writeBuffers.remaining();
  }

  @Override
  public int getMaxBufferSize() {
    return this.maxBufferSize;
  }

  @Override
  public SubmitterExecutorInterface getClientsThreadExecutor() {
    return sei;
  }

  @Override
  public SocketExecuterBase getClientsSocketExecuter() {
    return seb;
  }

  @Override
  public void setClientsSocketExecuter(SocketExecuterBase cse) {
    if(cse == null) {
      seb = cse;
    }
  }

  @Override
  public void setMaxBufferSize(int size) {
    if(size > 0) {
      maxBufferSize = size;
    } else {
      throw new IllegalArgumentException("Default size must be more then 0");
    }
  }

  @Override
  public ByteBuffer getRead() {
    ByteBuffer bb = null;
    synchronized(readBuffers) {
      if(readBuffers.remaining() == 0) {
        return null;
      }
      bb = readBuffers.pull(readBuffers.remaining());
    }
    if(getReadBufferSize() + bb.remaining() >= maxBufferSize &&  getReadBufferSize() < maxBufferSize) {
      seb.flagNewRead(this);
    }
    return bb;
  }

  @Override
  public ByteBuffer getWriteBuffer() {
    return null;
  }

  @Override
  public void reduceWrite(int size) {
    
  }

}
