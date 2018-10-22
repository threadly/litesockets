package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.buffers.SimpleMergedByteBuffers;
import org.threadly.litesockets.utils.IOUtils;

public class EmuSocketChannel extends SocketChannel {

  private static final Set<SocketOption<?>> SOCKET_OPTIONS;
  private static final int MAX_WRITE_BUFFER_SIZE = 1024*256;
  private static final int MAX_READ_BUFFER_SIZE = 1024*256;
  static {
    Set<SocketOption<?>> options = new HashSet<>();
    options.add(StandardSocketOptions.SO_RCVBUF);
    options.add(StandardSocketOptions.SO_SNDBUF);
    SOCKET_OPTIONS = Collections.unmodifiableSet(options);
  }

  private final ReuseableMergedByteBuffers readBuffer = new ReuseableMergedByteBuffers(false);
  private final ReuseableMergedByteBuffers writeBuffer = new ReuseableMergedByteBuffers(false);
  private final AtomicBoolean connecting = new AtomicBoolean(false);
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private volatile EmuSocketChannel connectedClient = null;
  private volatile ListenableFuture<EmuSocketChannel> connectingFuture = null;
  private volatile SocketAddress localSA;
  private volatile int maxWrite = Short.MAX_VALUE*2;
  private volatile int maxRead = Short.MAX_VALUE*2;
  private volatile boolean closed = false;
  private volatile boolean allowInput = true;  
  private volatile boolean allowOutput = true;
  private volatile boolean blocking = true;


  protected EmuSocketChannel(SelectorProvider sp) {
    super(sp);
  }

  protected EmuSocketChannel(SelectorProvider sp, EmuSocketChannel remotec, SocketAddress sa) {
    super(sp);
    connectedClient = remotec;
    connected.set(true);
    localSA = sa;

  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getOption(SocketOption<T> name) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    if(name == StandardSocketOptions.SO_RCVBUF) {
      return (T) Integer.valueOf(maxWrite);
    } else if(name == StandardSocketOptions.SO_SNDBUF) {
      return (T) Integer.valueOf(maxWrite);
    }
    return null;
  }

  @Override
  public <T> SocketChannel setOption(SocketOption<T> name, T value) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }   
    if(name == StandardSocketOptions.SO_RCVBUF) {
      int nr = (Integer) value;
      if(nr >= 1024) {
        maxRead = Math.min(MAX_READ_BUFFER_SIZE, nr);
      }
    } else if(name == StandardSocketOptions.SO_SNDBUF) {
      int ns = (Integer) value;
      if(ns >= 1024) {
        maxWrite = Math.min(MAX_WRITE_BUFFER_SIZE, ns);
      }
    }
    return this;
  }
  
  @Override
  public Set<SocketOption<?>> supportedOptions() {
    return SOCKET_OPTIONS;
  }

  @Override
  public SocketChannel bind(SocketAddress local) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    localSA = local;
    return this;
  }

  @Override
  public boolean connect(SocketAddress remote) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    if(blocking) {
      EmuServerSocketChannel ssc = EmuGlobalState.getGlobalState().findServerAddress(remote);
      if(connecting.compareAndSet(false, true)) {
        if(ssc != null) {
          connectingFuture = ssc.connectClient(this);
          try {
            connectedClient = connectingFuture.get(20, TimeUnit.SECONDS);
            connected.set(true);
            return true;
          } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException(e);
          }
        } else {
          closed = true;
          throw new IOException("Connection Refuesed");
        }
      } else {
        throw new AlreadyConnectedException();
      }
    } else {
      EmuServerSocketChannel ssc = EmuGlobalState.getGlobalState().findServerAddress(remote);
      if(connecting.compareAndSet(false, true)) {
        if(ssc != null) {
          connectingFuture = ssc.connectClient(this);
        } else {
          connectingFuture = FutureUtils.immediateFailureFuture(new IOException("Connection Refuesed"));
        }
        return false;
      } else {
        throw new ConnectionPendingException();
      }
    }
  }

  @Override
  public boolean finishConnect() throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    if(connected.get() && !closed && connectedClient != null) {
      return true;
    }
    if(connectingFuture == null) {
      throw new NoConnectionPendingException();
    } else if(closed) {
      throw new ClosedChannelException();
    } 
    if(connectingFuture.isDone()) {
      try {
        connectedClient = connectingFuture.get();
        connected.set(true);
        return true;
      } catch (ExecutionException e) {
        if(e.getCause() instanceof IOException) {
          throw (IOException)e.getCause(); 
        }
        throw new IOException(e.getCause());
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
    } else {
      return false;
    }
  }

  @Override
  public SocketAddress getLocalAddress() throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    return localSA;
  }

  @Override
  public SocketAddress getRemoteAddress() throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    return connectedClient.getRemoteAddress();
  }

  @Override
  public boolean isConnected() {
    if(closed) {
      return false;
    }
    return this.connected.get();
  }

  @Override
  public boolean isConnectionPending() {
    if(connected.get()) {
      return false;
    }
    return this.connecting.get();
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    return (int) read(new ByteBuffer[] {dst}, 0, dst.remaining());
  }

  @Override
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
    if(closed && readBuffer.remaining() == 0) {
      return -1;
    }
    updateReadBuffer();
    if(blocking) {
      synchronized(readBuffer) {
        int read = 0;
        int cb = offset;
        while(read == 0) {
          if(readBuffer.remaining() == 0) {
            try {
              readBuffer.wait();
            } catch (InterruptedException e) {
            }
            if(closed) {
              throw new AsynchronousCloseException();
            }
          }
          ByteBuffer rb = readBuffer.pullBuffer(Math.min(length, readBuffer.remaining()));
          read+=rb.remaining();
          while(rb.hasRemaining()) {
            dsts[cb].put(rb);
          }
        }
        return read;
      }
    } else {
      if(this.readBuffer.remaining() == 0) {
        return 0;
      } else {
        ByteBuffer rb = null;
        synchronized(readBuffer) {
          rb = readBuffer.pullBuffer(Math.min(length, readBuffer.remaining()));
        }
        int read = rb.remaining();
        if(dsts[offset].remaining() >= length) {
          ByteBuffer bb = dsts[offset];
          bb.put(rb);
        } else {
          int cb = offset;
          while(rb.hasRemaining()) {
            dsts[cb].put(rb);
          }
        }
        EmuGlobalState.getGlobalState().checkSelectors();
        return read;
      }
    }
  }

  private void updateReadBuffer() {
    synchronized(readBuffer) {
      if(readBuffer.remaining() == 0) {
        readBuffer.add(connectedClient.getWrite(maxRead));
      }
    }
  }


  @Override
  public SocketChannel shutdownInput() throws IOException {
    //TODO: implement
    if(closed) {
      throw new ClosedChannelException();
    }
    allowInput = false;
    return this;
  }

  @Override
  public SocketChannel shutdownOutput() throws IOException {
    //TODO: implement
    if(closed) {
      throw new ClosedChannelException();
    }
    allowOutput = false;
    return this;
  }

  @Override
  public Socket socket() {
    return new EmuSocket(this);
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    if(blocking) {
      int total = src.remaining();
      SimpleMergedByteBuffers smbb = new SimpleMergedByteBuffers(false, src);
      synchronized(writeBuffer) {
        writeBuffer.add(smbb.pullBuffer(Math.min(smbb.remaining(), maxWrite-writeBuffer.remaining())));
        while(writeBuffer.remaining() == this.maxWrite && smbb.remaining() > 0) {
          try {
            writeBuffer.wait();
          } catch (InterruptedException e) {
          }
          if(closed) {
            throw new AsynchronousCloseException();
          }
        }
      }
      return total;
    } else {
      int free = maxWrite - writeBuffer.remaining();
      if(free > 0) {
        if(free > src.remaining()) {
          int total = src.remaining();
          synchronized(writeBuffer) {
            writeBuffer.add(src);
          }
          EmuGlobalState.getGlobalState().checkSelectors();
          return total;
        } else {
          byte[] ba = new byte[Math.min(free, src.remaining())];
          src.get(ba);
          synchronized(writeBuffer) {
            writeBuffer.add(ba);
          }
          EmuGlobalState.getGlobalState().checkSelectors();
          return ba.length;
        }
      }
      return 0;      
    }

  }

  @Override
  public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    if(srcs[offset].remaining() == length) {
      return write(srcs[offset]);
    } else {
      byte[] ba = new byte[length];
      int cl = 0;
      int bp = offset;
      while(cl < length) {
        int bl = Math.min(srcs[bp].remaining(), length-cl);
        srcs[bp].get(ba, cl, bl);
        cl+=bl;
        bp++;
      }
      return write(ByteBuffer.wrap(ba));
    }
  }

  @Override
  protected void implCloseSelectableChannel() throws IOException {
    closed = true;
    synchronized(writeBuffer) {
      this.writeBuffer.notifyAll();
    }
    synchronized(readBuffer) {
      this.readBuffer.notifyAll();
    }
    if(connectedClient != null) {
      connectedClient.close();
    }
    EmuGlobalState.getGlobalState().checkSelectors();
  }

  @Override
  protected void implConfigureBlocking(boolean block) throws IOException {
    if(closed) {
      throw new ClosedChannelException();
    }
    blocking = block;
  }

  protected ByteBuffer getWrite(int size) {
    if(!closed) {
      synchronized(writeBuffer) {
        if(writeBuffer.remaining() > 0) {
          return writeBuffer.pullBuffer(Math.min(writeBuffer.remaining(),size));
        }
      }
    }
    return IOUtils.EMPTY_BYTEBUFFER;
  }

  protected int postRead(ByteBuffer bb) {
    if(closed) {
      return 0;
    }
    synchronized(readBuffer) {
      if(readBuffer.remaining() < this.maxRead) {
        if(readBuffer.remaining() + bb.remaining() <= this.maxRead) {
          int size = bb.remaining();
          readBuffer.add(bb);
          return size;
        } else {
          ByteBuffer rbb = ByteBuffer.allocate(readBuffer.remaining() + bb.remaining() - maxRead);
          int size = rbb.remaining();
          rbb.put(bb);
          rbb.flip();
          readBuffer.add(rbb);
          return size;
        }
      } else {
        return 0;
      }
    }
  }
  
  protected boolean pendingFutureWaiting() {
    return this.connectingFuture.isDone();
  }
  
  protected boolean pendingReads() {
    return connectedClient.writeBuffer.remaining() > 0;
  }
  
  protected boolean canWrite() {
    return writeBuffer.remaining() < this.maxWrite;
  }

}
