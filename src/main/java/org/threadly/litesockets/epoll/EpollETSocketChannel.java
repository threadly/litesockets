package org.threadly.litesockets.epoll;

import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

public class EpollETSocketChannel extends EpollETSelectableChannel {

  private static native long openSocketChannel();
  private static native long openSocketChannel(ProtocolFamily family);
  private static native boolean bindToAddress(long id, SocketAddress sa);
  private static native boolean connectToAddress(long id, SocketAddress sa);
  private static native boolean close(long id);
  private static native int write(long id, byte[] ba, int offset, int len);
  private static native byte[] read(long id);
  
  
  EpollETSocketChannel(long channelId) throws IOException {
    super(channelId);
  }
  
  public EpollETSocketChannel bind(SocketAddress sa) throws IOException {
    if(bindToAddress(getFD(), sa)) {
      return this;
    }
    throw new IOException("Could not bind to address:"+sa);
  }
  
  public EpollETSocketChannel connect(SocketAddress sa) throws IOException {
    if(connectToAddress(getFD(), sa)) {
      return this;
    }
    throw new IOException("Could not bind to address:"+sa);    
  }
  
  public int write(ByteBuffer bb) throws IOException {
    if(isClosed()) {
      throw new ClosedChannelException();
    }
    int size = write(getFD(), bb.array(), bb.position()+bb.arrayOffset(), bb.limit()+bb.arrayOffset());
    bb.position(bb.position()+size);
    return size;
  }
  
  public ByteBuffer read() throws IOException {
    return ByteBuffer.wrap(read(getFD()));
  }
  
  @Override
  public void closeSocket() {
    close(this.getFD());
  }

  public static EpollETSocketChannel open() throws IOException {
    return new EpollETSocketChannel(openSocketChannel());
  }
  
  public static EpollETSocketChannel open(ProtocolFamily family) throws IOException {
    return new EpollETSocketChannel(openSocketChannel(family));
  }
}
