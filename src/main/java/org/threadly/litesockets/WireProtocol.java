package org.threadly.litesockets;

/**
 * <p>Wire protocols supported by litesockets.  This is the protocol
 * used to communicate with. Depending on the protocol Implementors of the SocketExecuterInterface 
 * might need to take a different action.</p>
 * 
 */
public enum WireProtocol {
  FAKE, TCP, UDP
}
