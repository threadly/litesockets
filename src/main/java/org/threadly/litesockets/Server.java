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
 * <p>Both the {@link ClientAcceptor} and {@link ServerCloser} callbacks can happen on multiple threads so use thread safety when dealing
 * with those callbacks.</p>
 * 
 *
 */
public interface Server {
  
  /**
   * <p>Sets the Thread {@link Executor} that this Server uses.  This is set by the {@link SocketExecuterInterface} but can be overridden with 
   * little concern.</p>
   * 
   * @param executor A thread {@link Executor} that will be used by this Server object.
   */
  public void setThreadExecutor(Executor executor);
  
  /**
   * <p>Sets the current {@link SocketExecuterInterface} for this Server to use.  This is set by {@link SocketExecuterInterface#addServer(Server)}
   * and should probably not be changed.</p>
   * 
   * @param se {@link SocketExecuterInterface} to set.
   */
  public void setSocketExecuter(SocketExecuterInterface se);
  
  /**
   * <p>Gets the Current {@link SocketExecuterInterface} this Server is assigned to.</p>
   * 
   * @return the current {@link SocketExecuterInterface} for this Server.
   */
  public SocketExecuterInterface getSocketExecuter();
  
  /**
   * <p>Get the current ServerCloser callback assigned to this Server.</p>
   * 
   * @return the currently set Closer.
   */
  public ServerCloser getCloser();
  
  /**
   * <p>Set the {@link ServerCloser} for this Server.</p>
   * 
   * @param closer The {@link ServerCloser} to set for this Server. 
   */
  public void setCloser(ServerCloser closer);
  
  /**
   * <p>This is how the extending server receives the {@link SelectableChannel}.
   * At this point it needs to do what is needed to turn this Channel into
   * A Client object for this type of server.</p>
   * 
   * @param c The {@link SelectableChannel} that was just accepted by this Server.
   */
  public void acceptChannel(SelectableChannel c);
  
  /**
   * <p>This is used by the {@link SocketExecuterInterface} to know how to handle this Server 
   * when its added to it.  Currently only UDP or TCP.</p>
   * 
   * @return returns the type of protocol this socket uses.
   */
  public WireProtocol getServerType();
  
  /**
   * <p>Get the {@link SelectableChannel} used by this Server.</p>
   * 
   * @return the {@link SelectableChannel} for this server.
   */
  public SelectableChannel getSelectableChannel();
  
  /**
   * <p>Gets the current {@link ClientAcceptor} Callback for this Server.</p> 
   * 
   * @return the currently set {@link ClientAcceptor}.
   */
  public ClientAcceptor getClientAcceptor();
  
  /**
   * <p>Set the {@link ClientAcceptor} for this Server.  This should be set before the Server is added to the {@link SocketExecuterInterface}.
   * If its not you could miss pending client connections.</p>
   *   
   * @param clientAcceptor Sets the {@link ClientAcceptor} callback for this server.
   */
  public void setClientAcceptor(ClientAcceptor clientAcceptor);
  
  /**
   * <p>Close this servers Socket.  Once closed you must construct a new Server to open it again.</p>
   */
  public void close();
  
  /**
   * <p>This is the ClientAcceptor callback for the {@link Server}.  This is called when a new {@link Client} is 
   * detected for this server.</p>
   * 
   * <p>NOTE: This will/can be called from many threads at once.</p>
   *
   */
  public interface ClientAcceptor {
    /**
     * This is called when a new Client is added by this {@link Server}.
     * 
     * @param client The new {@link Client} object created.
     */
    public void accept(Client client);
  }
  
  /**
   * <p>This is called once a Close is detected on the Servers Socket. Since it can happen on any thread as well
   * you might get new clients for this server shortly after it closes.</p>
   * 
   *
   */
  public interface ServerCloser {
    /**
     * Once a close is detected for this {@link Server} this is called..
     * 
     * @param server The {@link Server} that has been closed.
     */
    public void onClose(Server server);
  }

}
