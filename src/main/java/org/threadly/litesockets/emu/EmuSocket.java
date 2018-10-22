package org.threadly.litesockets.emu;

import java.net.Socket;

public class EmuSocket extends Socket {
  
  private final EmuSocketChannel esc;

  EmuSocket(EmuSocketChannel esc) {
    this.esc = esc;
  }
  
  //TODO: Should there be more here?  
}
