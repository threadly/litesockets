package org.threadly.litesockets;

import java.nio.channels.SelectableChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.litesockets.SocketExecuterInterface.WireProtocol;

/**
 * This is the main Server object for litesockets.  
 * Any type of connection/open port will use this to create that open port
 * and accept client or send data with it.
 * 
 * The Server has an Acceptor callback and a Closer callback.
 * 
 * Both the Acceptor and Closer callbacks happen on multiple threads so use thread safety when dealing
 * with those callbacks.
 * 
 * @author lwahlmeier
 *
 */
public abstract class Server {
  
  private volatile ServerCloser closer;
  protected volatile Executor sei;
  protected volatile SocketExecuterInterface se;
  protected AtomicBoolean closed = new AtomicBoolean(false);
  
  /**
   * Sets the ThreadExecuter that this Server uses
   * 
   * @param sei ThreadExecuter to set to.
   */
  protected void setThreadExecuter(Executor sei) {
    this.sei = sei;
  }
  
  /**
   * Sets the current SocketExecuter for this Server to use
   * 
   * @param se SocketExecuter to set.
   */
  protected void setServerExecuter(SocketExecuterInterface se) {
    this.se = se;
  }
  
  /**
   * Gets the Current SocketExecuter this Server is assigned to.
   * 
   * @return the current SocketExecuter for this Server.
   */
  protected SocketExecuterInterface getServerExecuter() {
    return this.se;
  }
  
  /**
   * Get the current ServerCloser callback assigned to this Server.
   * 
   * @return the currently set Closer.
   */
  public ServerCloser getCloser() {
    return closer;
  }

  /**
   * Set the ServerCloser for this server.
   * 
   * @param closer The ServerCloser to set for this Server. 
   */
  public void setCloser(ServerCloser closer) {
    this.closer = closer;
  }
  
  /**
   * Called when a Server Socket close is detected.
   */
  protected void callCloser() {
    if(sei != null && closer != null) {
      sei.execute(new Runnable() {
        @Override
        public void run() {
          getCloser().onClose(Server.this);
        }});
    }
  }
  
  /**
   * This is called when a new SocketChannel is created for this server.
   * 
   * @param client SelectableChannel that was created.
   */
  protected void callAcceptor(final SelectableChannel client) {
    if(sei != null ) {
      sei.execute(new Runnable() {
        @Override
        public void run() {
          accept(client);
        }});
    }
  }
  /**
   * This is how the extending server receives the SelectableChannel.
   * At this point it needs to do what is needed to turn this Channel into
   * A client of some kind.
   * 
   * @param c The SelectableChannel that was just accepted by this Server.
   */
  public abstract void accept(SelectableChannel c);
  
  /**
   * This is used by the SocketExecuter to know how to handle this Server 
   * when its added to it.
   * 
   * @return returns the type of protocol this socket uses.
   */
  public abstract WireProtocol getServerType();
  
  /**
   * Get the SelectableChannel used by this Server.
   * 
   * @return the SelectableChannel for this server.
   */
  public abstract SelectableChannel getSelectableChannel();
  
  /**
   * Gets the current ClientAcceptor Callback for this Server. 
   * 
   * @return the currently set clientAcceptor.
   */
  public abstract ClientAcceptor getClientAcceptor();
  
  /**
   * Set the ClientAcceptor for this Server.
   *   
   * @param clientAcceptor Sets the ClientAcceptor callback for this server.
   */
  public abstract void setClientAcceptor(ClientAcceptor clientAcceptor);
  
  /**
   * Close this servers Socket.  Once closed you must construct a new Server to open it again.
   */
  public abstract void close();
  
  /**
   * This is the clientAcceptor interface for the Server.  This is called when a new Client is detected.
   * This can be called from many threads at once.
   * 
   * @author lwahlmeier
   *
   */
  public interface ClientAcceptor {
    public void accept(Client c);
  }
  
  /**
   * This is called once a Close is detected on the Servers Socket. Since it can happen on any thread as well
   * you might get new clients for this server shortly after it closes.
   * 
   * 
   * @author lwahlmeier
   *
   */
  public interface ServerCloser {
    public void onClose(Server server);
  }

}
