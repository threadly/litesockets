package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class EmuGlobalState {
  private static final EmuGlobalState GLOBAL_STATE = new EmuGlobalState();
  
  private final ConcurrentHashMap<SocketAddress, EmuSocketChannel> clientStates = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<SocketAddress, EmuServerSocketChannel> serverStates = new ConcurrentHashMap<>();
  private final CopyOnWriteArraySet<EmuSelector> allSelectors = new CopyOnWriteArraySet<>();
  
  EmuGlobalState() {}
  
  public static EmuGlobalState getGlobalState() {
    return GLOBAL_STATE;
  }
  
  public EmuServerSocketChannel findServerAddress(SocketAddress sa) {
    return serverStates.get(sa);
  }
  
  public EmuSocketChannel findClientAddress(SocketAddress sa) {
    return clientStates.get(sa);
  }
  
  public boolean removeSocketChannel(EmuSocketChannel sc) {
    if(clientStates.containsValue(sc)) {
      try {
        return clientStates.remove(sc.getLocalAddress()) != null;
      } catch (IOException e) {
        return false;
      }
    }
    return false;
  }
  
  public void addSelector(EmuSelector s) {
    allSelectors.add(s);
  }
  
  public void removeSelector(EmuSelector s) {
    allSelectors.remove(s);
  }
  
  public void checkSelectors() {
    for(EmuSelector s: allSelectors) {
      s.check();
    }
  }
  
  public boolean addSocketChannel(EmuSocketChannel sc) {
    try {
      EmuSocketChannel t = clientStates.putIfAbsent(sc.getLocalAddress(), sc);
      if(t == null) {
        return true;
      } else {
        return false;
      }
    } catch (IOException e) {
      return false;
    }
  }
  
  public boolean removeServerSocketChannel(EmuServerSocketChannel sc) {
    if(serverStates.containsValue(sc)) {
      try {
        return serverStates.remove(sc.getLocalAddress()) != null;
      } catch (IOException e) {
        return false;
      }
    }
    return false;
  }
  
  public boolean addServerSocketChannel(EmuServerSocketChannel sc) {
    try {
      EmuServerSocketChannel t = serverStates.putIfAbsent(sc.getLocalAddress(), sc);
      if(t == null) {
        return true;
      } else {
        return false;
      }
    } catch (IOException e) {
      return false;
    }
  }
}
