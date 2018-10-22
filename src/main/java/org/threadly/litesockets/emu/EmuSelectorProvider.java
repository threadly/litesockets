package org.threadly.litesockets.emu;

import java.io.IOException;
import java.net.ProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

public class EmuSelectorProvider extends SelectorProvider {
  
  private static final EmuSelectorProvider sp =  new EmuSelectorProvider();
  
  public static EmuSelectorProvider instance() {
    return sp;
  }

  @Override
  public DatagramChannel openDatagramChannel() throws IOException {
    return new EmuDatagramChannel(this);
  }

  @Override
  public DatagramChannel openDatagramChannel(ProtocolFamily family) throws IOException {
    return new EmuDatagramChannel(this, family);
  }

  @Override
  public Pipe openPipe() throws IOException {
    return new EmuPipe(this);
  }

  @Override
  public AbstractSelector openSelector() throws IOException {
    return new EmuSelector(this);
  }

  @Override
  public ServerSocketChannel openServerSocketChannel() throws IOException {
    return new EmuServerSocketChannel(this);
  }

  @Override
  public SocketChannel openSocketChannel() throws IOException {
    return new EmuSocketChannel(this);
  }

}
