package org.threadly.litesockets.emu;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelectionKey;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.util.Clock;

public class EmuSelector extends AbstractSelector {
  
  private final ConcurrentHashMap<AbstractSelectableChannel, EmuSelectionKey> keys = new ConcurrentHashMap<>();
  private final Object lock = new Object();
  private volatile Set<SelectionKey> readyKeys = Collections.emptySet();
  private volatile boolean wakeup = false;


  protected EmuSelector(SelectorProvider provider) {
    super(provider);
    EmuGlobalState.getGlobalState().addSelector(this);
  }
  
  public static Selector open() {
    return new EmuSelector(EmuSelectorProvider.instance());
  }

  @Override
  protected void implCloseSelector() throws IOException {
    keys.clear();
    EmuGlobalState.getGlobalState().removeSelector(this);
  }

  @Override
  protected SelectionKey register(AbstractSelectableChannel ch, int ops, Object att) {
    EmuSelectionKey key = keys.get(ch);
    if(key != null) {
      if(key.attachment() != att) {
        key.attach(att);
      }
      key.interestOps(ops);
    }
    if(ch instanceof EmuServerSocketChannel) {
      key = new EmuServerSelectionKey(this, (EmuServerSocketChannel)ch, att, ops); 
    } else if (ch instanceof EmuSocketChannel){
      key = new EmuSocketChannelSelectionKey(this, (EmuSocketChannel)ch, att, ops);
    }
    
    key = keys.putIfAbsent(ch, key);
    if(key == null) {
      key = keys.get(ch);
    }
    return key;
  }

  @Override
  public Set<SelectionKey> keys() {
    return new HashSet<SelectionKey>(keys.values());
  }

  @Override
  public int select() throws IOException {
    return select(Long.MAX_VALUE);
  }

  @Override
  public int selectNow() throws IOException {
    return select(0);
  }
  
  @Override
  public int select(long delayms) throws IOException {
    if(delayms <= 3) {
      checkKeys();
      return readyKeys.size();
    } else {
      long et = Clock.accurateForwardProgressingMillis()+delayms;
      long waitTime = et - Clock.accurateForwardProgressingMillis();
      while(waitTime > 0) {
        checkKeys();
        if(readyKeys.size() > 0 || wakeup) {
          wakeup = false;
          return readyKeys.size();
        }
        synchronized(lock) {
          try {
            lock.wait(waitTime);
          } catch (InterruptedException e) {

          }
        }
        waitTime = et - Clock.accurateForwardProgressingMillis();
      }

    }
    return 0;
  }

  private void checkKeys() {
    Set<SelectionKey> newKeys = new HashSet<>();
    for(EmuSelectionKey esk: keys.values()) {
      if(esk.hasOperationReady()) {
        newKeys.add(esk);
      }
    }
    readyKeys = newKeys;
  }

  @Override
  public Set<SelectionKey> selectedKeys() {
    return readyKeys;
  }
  
  protected Selector check() {
    synchronized(lock) {
      lock.notify();
    }
    return this;
  }

  @Override
  public Selector wakeup() {
    synchronized(lock) {
      wakeup = true;
      lock.notify();
    }
    return this;
  }
  
  public static abstract class EmuSelectionKey extends AbstractSelectionKey {
    public abstract boolean hasOperationReady();
  }

  public static class EmuServerSelectionKey extends EmuSelectionKey {
    private final EmuServerSocketChannel ch;
    private final EmuSelector selector;
    private volatile int ops;
    
    EmuServerSelectionKey(EmuSelector selector, EmuServerSocketChannel ch, Object attached, int ops) {
      this.ch = ch;
      this.selector = selector;
      this.ops = ops;
      attach(attached);
    }
 
    @Override
    public SelectableChannel channel() {
      return ch;
    }

    @Override
    public int interestOps() {
      return ops;
    }

    @Override
    public SelectionKey interestOps(int arg0) {
      ops = arg0;
      selector.wakeup();
      return this;
    }

    @Override
    public int readyOps() {
      if(ch.pendingClients() > 0) {
        return SelectionKey.OP_ACCEPT;
      }
      return 0;
    }

    @Override
    public Selector selector() {
      return selector;
    }

    @Override
    public boolean hasOperationReady() {
      if((ops & SelectionKey.OP_ACCEPT) != 0) {
        return ch.pendingClients() > 0;
      } else {
         return false;
      }
    }
  }
  
  public static class EmuSocketChannelSelectionKey extends EmuSelectionKey {
    private final EmuSocketChannel ch;
    private final EmuSelector selector;
    private volatile int ops;
    
    EmuSocketChannelSelectionKey(EmuSelector selector, EmuSocketChannel ch, Object attached, int ops) {
      this.ch = ch;
      this.selector = selector;
      this.ops = ops;
      attach(attached);
    }

    @Override
    public SelectableChannel channel() {
      return ch;
    }

    @Override
    public int interestOps() {
      return ops;
    }

    @Override
    public SelectionKey interestOps(int arg0) {
      ops = arg0;
      selector.wakeup();
      return this;
    }

    @Override
    public int readyOps() {
      return ops;
    }

    @Override
    public Selector selector() {
      return selector;
    }

    @Override
    public boolean hasOperationReady() {
      if((ops & SelectionKey.OP_CONNECT) != 0 && ch.isConnectionPending()) {
        if(ch.isOpen() && ch.pendingFutureWaiting()) {
          return true;
        }
      }
      if((ops & SelectionKey.OP_WRITE) != 0 && ch.canWrite()) {
        return true;
      }
      if((ops & SelectionKey.OP_READ) != 0 && (ch.pendingReads() || !ch.isOpen())) {
        return true;
      }
      return false;
    }
  }
}
