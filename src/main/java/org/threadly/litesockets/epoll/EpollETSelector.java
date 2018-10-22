package org.threadly.litesockets.epoll;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EpollETSelector {
  
  public static enum SelectionType {
    Accept, Read, Write
  }
  
//  private static native long openSelector();
//  private static native long closeSelector(long id);
//  private static native long[] doSelect(long id, long timeout);
//  private static native void doWakeUp(long id);
//  
//  private final Set<EpollETSelectableChannel> keys = new HashSet<>();
//  private final Set<EpollETSelectableChannel> selectedKeys = new HashSet<>();
//  private final long selectorId;
//  private volatile boolean isOpen = true;
//  
//  private EpollETSelector() {
//    selectorId = openSelector();
//  }
//
//  public static EpollETSelector open() {
//    return new EpollETSelector();
//  }
//  
//  public void close() throws IOException {
//    isOpen = false;
//    closeSelector(selectorId);
//  }
//  
//  public boolean isOpen() {
//    return isOpen;
//  }
//  
//  public Set<EpollETSelectableChannel> keys() {
//    return keys;
//  }
//  
//  public void addKey
//  
//  public int select() throws IOException {
//    long[] fds = doSelect(selectorId, Long.MAX_VALUE);
//    return fds.length;
//  }
//  
//  public int select(long timeout) throws IOException {
//    long[] fds = doSelect(selectorId, timeout);
//    return fds.length;
//  }
//  
//  public int selectNow() throws IOException {
//    long[] fds = doSelect(selectorId, 0);
//    selectedKeys.addAll(Arrays.asList(fds));
//    return fds.length;
//  }
//  
//  public Set<EpollETSelectableChannel> selectedChannels() {
//    return selectedKeys;
//  }
//  
//  public Selector wakeup() {
//    doWakeUp(this.selectorId);
//    return this;
//  }
//  
//  private Set<SelectionKey> fdToSK(long[] fds) {
//    Set<SelectionKey> ret = new HashSet<>();
//    for(SelectionKey sk: keys) {
//      if(sk.channel())
//    }
//    return ret;
//  }
}
