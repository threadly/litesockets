package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;

public class EmuServerSocket extends ServerSocket {

  private final EmuServerSocketChannel ssc;
  
  protected EmuServerSocket(EmuServerSocketChannel essc) throws IOException {
    this.ssc = essc;
  }
  
  @Override
  public void bind(SocketAddress local) throws IOException {
    ssc.bind(local);
  }
  public void bind(SocketAddress local, int backlog) throws IOException {
    ssc.bind(local, backlog);
  }
  public InetAddress getInetAddress() {
    try {
      return ((InetSocketAddress)ssc.getLocalAddress()).getAddress();
    } catch (IOException e) {
      return null;
    }
  }

  public int getLocalPort() {
    try {
      return ((InetSocketAddress)ssc.getLocalAddress()).getPort();
    } catch (IOException e) {
      return -1;
    }
  }
  public Socket accept() throws IOException {
    return ssc.accept().socket();
  }
  public void close() throws IOException {
    ssc.close();
  }
  public ServerSocketChannel getChannel() {
    return ssc;
  }
  public boolean isBound() {
    return ssc.isBound();
  }

  public boolean isClosed() {
    return !ssc.isOpen();
  }
  
  public void setSoTimeout(int timeout) throws SocketException {
    
  }
  
  public int getSoTimeout() throws SocketException {
    return -1;
  }
  public void setReuseAddress(boolean on) throws SocketException {
    
  }
  public boolean getReuseAddress() throws SocketException {
    return false;
  }
  public void setReceiveBufferSize(int size) throws SocketException {
    
  }
  public int getReceiveBufferSize() throws SocketException {
    return -1;
  }
}
