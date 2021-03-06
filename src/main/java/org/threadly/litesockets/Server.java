package org.threadly.litesockets;

import java.io.Closeable;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.threadly.concurrent.event.ListenerHelper;
import org.threadly.util.ExceptionUtils;

/**
 * This is the main Server Interface for litesockets.  
 * 
 * <p>Any type of connection/open port will use this to "Accept" new client connections on. 
 * The Server has an Acceptor callback for new clients and a Closer callback for clean up when the socket is closed.</p>
 * 
 * <p>Both the {@link ClientAcceptor} and {@link ServerCloseListener} callbacks can happen on multiple threads so use thread safety when dealing
 * with those callbacks.</p>
 * 
 *
 */
public abstract class Server implements Closeable {
  protected final SocketExecuterCommonBase sei;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private volatile ClientAcceptor clientAcceptor;
  private volatile ListenerHelper<ServerCloseListener> closer = 
      new ListenerHelper<>(ServerCloseListener.class);
  
  protected Server(final SocketExecuterCommonBase sei) {
    this.sei = sei;
  }
  
  /**
   * <p>This is how the extending server receives the {@link SelectableChannel}.
   * At this point it needs to do what is needed to turn this Channel into
   * A Client object for this type of server.</p>
   * 
   * @param c The {@link SelectableChannel} that was just accepted by this Server.
   */
  protected abstract void acceptChannel(SelectableChannel c);
  
  /**
   * <p>Get the {@link SelectableChannel} used by this Server.</p>
   * 
   * @return the {@link SelectableChannel} for this server.
   */
  protected abstract SelectableChannel getSelectableChannel();
  
  /**
   * <p>Close this servers Socket.  Once closed you must construct a new Server to open it again.</p>
   */
  public void close() {
    close(null);
  }

  /**
   * <p>Close this servers Socket.  Once closed you must construct a new Server to open it again.</p>
   * 
   * @param error The error that resulted in us closing this server, or {@code null} if closing normally
   */
  public abstract void close(Throwable error);
  
  /**
   * <p>This is used by the {@link SocketExecuter} to know how to handle this Server 
   * when its added to it.  Currently only UDP or TCP.</p>
   * 
   * @return returns the type of protocol this socket uses.
   */
  public abstract WireProtocol getServerType();
  
  
  protected boolean setClosed() {
    return this.closed.compareAndSet(false, true);
  }

  /**
   * <p>Get the current ServerCloser callback assigned to this Server.</p>
   * 
   * @param t Error that initiated the close, or {@code null} if closed normally
   */
  protected void callClosers(Throwable t) {
    if (t == null) {
      this.closer.call().onClose(this);
    } else {
      this.closer.call().onCloseWithError(this, t);
    }
  }

  /**
   * Tells the Server to start accepting connections.
   */
  public void start() {
    sei.startListening(this);
  }
  
  /**
   * Tells the Server to stop accepting connections.  The Listen port is still open and you
   * can call {@link #start()} to start listening again.  Use {@link #close()} to close the Listen port.
   */
  public void stop() {
    sei.stopListening(this);
  }
  
  /**
   * <p>Gets the Current {@link SocketExecuter} this Server is assigned to.</p>
   * 
   * @return the current {@link SocketExecuter} for this Server.
   */
  public SocketExecuter getSocketExecuter() {
    return sei;
  }
  
  /**
   * <p>Adds a {@link ServerCloseListener} for this Server.</p>
   * 
   * @param listener The {@link ServerCloseListener} to set for this Server. 
   */
  public void addCloseListener(final ServerCloseListener listener) {
    this.closer.addListener(listener);
  }
  
  /**
   * <p>Gets the current {@link ClientAcceptor} Callback for this Server.</p> 
   * 
   * @return the currently set {@link ClientAcceptor}.
   */
  public ClientAcceptor getClientAcceptor() {
    return this.clientAcceptor;
  }
  
  /**
   * <p>Set the {@link ClientAcceptor} for this Server.  This should be set before the Server is added to the {@link SocketExecuter}.
   * If its not you could miss pending client connections.</p>
   *   
   * @param acceptor Sets the {@link ClientAcceptor} callback for this server.
   */
  public void setClientAcceptor(final ClientAcceptor acceptor) {
    clientAcceptor = acceptor;
  }
  
  /**
   * Tells you if this sever objects Listen port is still open or not.
   * 
   * @return true if the socket is closed, false if the socket is open.
   */
  public boolean isClosed() {
    return closed.get();
  }
  
  /**
   * Used to notify the when a new {@link Client} has been created for a {@link Server}
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
   * Used to notify when a {@link Server} connection is closed.
   * 
   * <p>This is called once a Close is detected on the Servers Socket. Since it can happen on any thread as well
   * you might get new clients for this server shortly after it closes.</p>
   */
  public interface ServerCloseListener {
    /**
     * Invoked once the provided server has been detected to be in a closed state.
     * 
     * @param server The {@link Server} that has been closed.
     */
    public void onClose(Server server);

    /**
     * Invoked once the provided server has been closed due to an error.
     * 
     * <p>By default this will invoked {@link ExceptionUtils#handleException(Throwable)} and then 
     * invoke {@link #clone()}.  If you override this you must also invoke {@link #close()} if you 
     * want that logic shared / reused during an error condition.</p>
     * 
     * @param server The {@link Server} that has been closed.
     * @param error The exception which resulted in the client closing
     */
    public default void onCloseWithError(Server server, Throwable error) {
      ExceptionUtils.handleException(error);
      onClose(server);
    }
  }
}
