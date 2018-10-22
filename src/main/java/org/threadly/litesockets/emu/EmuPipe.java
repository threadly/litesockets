package org.threadly.litesockets.emu;

import java.nio.channels.Pipe;
import java.nio.channels.spi.SelectorProvider;

public class EmuPipe extends Pipe {

  EmuPipe(SelectorProvider sp) {
    
  }
  
  @Override
  public SinkChannel sink() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SourceChannel source() {
    // TODO Auto-generated method stub
    return null;
  }

}
