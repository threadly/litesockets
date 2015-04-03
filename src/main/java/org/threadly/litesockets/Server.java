package org.threadly.litesockets;

import java.nio.channels.SelectableChannel;
import java.util.concurrent.Executor;

import org.threadly.litesockets.SocketExecuterInterface.WireProtocol;

/**
 * This is the main Server Interface for litesockets.  
 * 
 * <p>Any type of connection/open port will use this to "Accept" new client connections on. 
 * The Server has an Acceptor callback for new clients and a Closer callback for clean up when the socket is closed.</p>
 * 
 * <p>Both the Acceptor and Closer callbacks can happen on multiple threads so use thread safety when dealing
 * with those callbacks.</p>
 * 
 *
 */
public interface Server {
  
  /**
   * <p>Sets the Thread Executor that this Server uses.  This is set by the SocketExecuter but can be overridden with 
   * little concern.</p>
   * 
   * @param sei Thread Executor to set to.
   */
  public void setThreadExecutor(Executor sei);
  
  /**
   * <p>Sets the current SocketExecuter for this Server to use.  This is set by the SocketExecuter on addServer
   * and should probably not be changed.</p>
   * 
   * @param se SocketExecuter to set.
   */
  public void setSocketExecuter(SocketExecuterInterface se);
  
  /**
   * <p>Gets the Current SocketExecuter this Server is assigned to.</p>
   * 
   * @return the current SocketExecuter for this Server.
   */
  public SocketExecuterInterface getSocketExecuter();
  
  /**
   * <p>Get the current ServerCloser callback assigned to this Server.</p>
   * 
   * @return the currently set Closer.
   */
  public ServerCloser getCloser();
  
  /**
   * <p>Set the ServerCloser for this server.</p>
   * 
   * @param closer The ServerCloser to set for this Server. 
   */
  public void setCloser(ServerCloser closer);
  
  /**
   * <p>This is how the extending server receives the SelectableChannel.
   * At this point it needs to do what is needed to turn this Channel into
   * A Client object for this type of server.</p>
   * 
   * @param c The SelectableChannel that was just accepted by this Server.
   */
  public void acceptChannel(SelectableChannel c);
  
  /**
   * <p>This is used by the SocketExecuter to know how to handle this Server 
   * when its added to it.  Currently only UDP or TCP.</p>
   * 
   * @return returns the type of protocol this socket uses.
   */
  public WireProtocol getServerType();
  
  /**
   * <p>Get the SelectableChannel used by this Server.</p>
   * 
   * @return the SelectableChannel for this server.
   */
  public SelectableChannel getSelectableChannel();
  
  /**
   * <p>Gets the current ClientAcceptor Callback for this Server.</p> 
   * 
   * @return the currently set clientAcceptor.
   */
  public ClientAcceptor getClientAcceptor();
  
  /**
   * <p>Set the ClientAcceptor for this Server.  This should be set before the Server is added to the SocketExecuter.
   * If its not you could miss pending client connections.</p>
   *   
   * @param clientAcceptor Sets the ClientAcceptor callback for this server.
   */
  public void setClientAcceptor(ClientAcceptor clientAcceptor);
  
  /**
   * <p>Close this servers Socket.  Once closed you must construct a new Server to open it again.</p>
   */
  public void close();
  
  /**
   * <p>This is the clientAcceptor interface for the Server.  This is called when a new Client is detected.
   * This can be called from many threads at once.</p>
   * 
   *
   */
  public interface ClientAcceptor {
    public void accept(Client c);
  }
  
  /**
   * <p>This is called once a Close is detected on the Servers Socket. Since it can happen on any thread as well
   * you might get new clients for this server shortly after it closes.</p>
   * 
   *
   */
  public interface ServerCloser {
    public void onClose(Server server);
  }

}
