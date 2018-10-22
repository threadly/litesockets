package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;

public class EmuServerSocketChannel extends ServerSocketChannel {

  private final LinkedList<Clients> pendingClients= new LinkedList<>();
  private final SelectorProvider sp;
  private final AtomicBoolean bound = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private volatile SocketAddress socketAddress = null;
  private volatile boolean blocking = false;
  private volatile int backlog = 0;  //TODO: should we implement?

  protected EmuServerSocketChannel(SelectorProvider sp) {
    super(sp);
    this.sp = sp;
  }

  @Override
  public <T> T getOption(SocketOption<T> arg0) throws IOException {
    return null;
  }

  @Override
  public Set<SocketOption<?>> supportedOptions() {
    return Collections.emptySet();
  }

  @Override
  public SocketChannel accept() throws IOException {
    if(blocking) {
      synchronized(pendingClients) {
        while(pendingClients.size() == 0) {
          if(closed.get()) {
            throw new AsynchronousCloseException();
          }
          try {
            pendingClients.wait();
          } catch (InterruptedException e) {

          }
        }
        Clients c = pendingClients.pollFirst();
        if(!c.remotec.isOpen()) {
          return c.localc;
        } else {
          throw new ClosedChannelException();
        }
      }
    } else {
      Clients c = pendingClients.pollFirst();
      if(c!=null) {
        if(c.remotec.isOpen()) {
          return c.localc;
        } else {
          throw new ClosedChannelException();
        }
      } else {
        return null;
      }
    }
  }

  @Override
  public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
    if(!bound.get()) {
      if(!closed.get()) {
        socketAddress = local;
        if(EmuGlobalState.getGlobalState().addServerSocketChannel(this) && bound.compareAndSet(false, true)) {
          this.backlog = backlog;
          return this;
        } else {
          throw new IOException("Address Already in use!");
        }
      } else {
        throw new IOException("Channel Closed!");
      }
    } else {
      throw new IOException("Channel Aready bound!");
    }
  }

  @Override
  public SocketAddress getLocalAddress() throws IOException {
    return socketAddress;
  }

  @Override
  public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
    return this;
  }

  @Override
  public ServerSocket socket() {
    try {
      return new EmuServerSocket(this);
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  protected void implCloseSelectableChannel() throws IOException {
    if(closed.compareAndSet(false, true)) {
      EmuGlobalState.getGlobalState().removeServerSocketChannel(this);
    }
  }

  @Override
  protected void implConfigureBlocking(boolean b) throws IOException {
    blocking = b;
  }

  public boolean isBound() {
    return bound.get();
  }
  
  protected int pendingClients() {
    return pendingClients.size();
  }
  
  protected ListenableFuture<EmuSocketChannel> connectClient(EmuSocketChannel sc) {
    SettableListenableFuture<EmuSocketChannel> slf = new SettableListenableFuture<>(false);
    EmuSocketChannel lc = new EmuSocketChannel(sp, sc, socketAddress);
    slf.setResult(lc);
    Clients c = new Clients(sc, lc, slf);
    synchronized(pendingClients) {
      pendingClients.add(c);
      pendingClients.notifyAll();
    }
    EmuGlobalState.getGlobalState().checkSelectors();
    return slf;
  }
  
  private static class Clients {
    private final EmuSocketChannel remotec;
    private final EmuSocketChannel localc;
    private final SettableListenableFuture<EmuSocketChannel> slf;
    
    public Clients(EmuSocketChannel remotec, EmuSocketChannel localc, SettableListenableFuture<EmuSocketChannel> slf) {
      this.remotec = remotec;
      this.localc = localc;
      this.slf = slf;
    }
  }
}
