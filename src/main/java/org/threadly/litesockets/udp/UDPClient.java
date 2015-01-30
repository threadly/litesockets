package org.threadly.litesockets.udp;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.threadly.concurrent.SubmitterExecutorInterface;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterBase.WireProtocol;

public class UDPClient extends Client {
  protected final SocketAddress sa;
  protected final UDPServer udpServer;
  
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
  protected void setThreadExecuter(SubmitterExecutorInterface  sei) {
    super.setThreadExecuter(sei);
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
    this.callCloser();
  }
  
  @Override
  protected void addReadBuffer(ByteBuffer bb) {
    super.addReadBuffer(bb);
  }
  
  @Override
  protected void callReader() {
    super.callReader();
  }
  
  @Override
  public void writeForce(ByteBuffer bb) {
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
    super.setReader(reader);
  }
  
  @Override
  public Reader getReader() {
    return super.getReader();
  }
  
  @Override
  public void setCloser(Closer closer) {
    super.setCloser(closer);
  }
  
  @Override
  public Closer getCloser() {
    return super.getCloser();
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.UDP;
  }

}
