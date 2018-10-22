package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

public class EmuDatagramChannel extends DatagramChannel{

  protected EmuDatagramChannel(SelectorProvider arg0) {
    super(arg0);
  }
  
  protected EmuDatagramChannel(SelectorProvider arg0, ProtocolFamily family) {
    super(arg0);
  }

  @Override
  public MembershipKey join(InetAddress arg0, NetworkInterface arg1) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public MembershipKey join(InetAddress arg0, NetworkInterface arg1, InetAddress arg2) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> T getOption(SocketOption<T> name) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Set<SocketOption<?>> supportedOptions() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public DatagramChannel bind(SocketAddress local) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public DatagramChannel connect(SocketAddress remote) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public DatagramChannel disconnect() throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SocketAddress getLocalAddress() throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SocketAddress getRemoteAddress() throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean isConnected() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public SocketAddress receive(ByteBuffer dst) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public int send(ByteBuffer src, SocketAddress target) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public <T> DatagramChannel setOption(SocketOption<T> name, T value) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public DatagramSocket socket() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  protected void implCloseSelectableChannel() throws IOException {
    // TODO Auto-generated method stub
    
  }

  @Override
  protected void implConfigureBlocking(boolean block) throws IOException {
    // TODO Auto-generated method stub
    
  }

}
