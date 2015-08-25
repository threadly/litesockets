package org.threadly.litesockets;

import org.threadly.concurrent.SimpleScheduler;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.ServiceInterface;

/**
 * This is the main Interface that Clients and Servers are added to, to run there socket operations.
 * 
 * 
 * @author lwahlmeier
 *
 */
public interface SocketExecuterInterface extends ServiceInterface {
  /**
   * <p>Wire protocols supported by litesockets.  This is the protocol
   * used to communicate with. Depending on the protocol Implementors of the SocketExecuterInterface 
   * might need to take a different action.</p>
   * 
   */
  public static enum WireProtocol {TCP, UDP}
  
  /**
   * <p>Add a {@link Client} to this SocketExecuter.  If the client needs to be connected the SocketExecuterInterfaces Implementor
   * must finish the connection, once done the client will be able to read and write to the socket.</p>
   * 
   * @param client the {@link Client} you are adding.
   */
  public void addClient(Client client);
  
  /**
   * <p>Remove a {@link Client} that has been added to this SocketExecuter.  Once this is done the client will
   * no longer be able to read/write to the socket.  If a write buffer for the client exists it might not be 
   * completely flushed out yet.  All reads should be at least queued in the Executer for a Read action.</p>
   * 
   * @param client the {@link Client} to remove from the SocketExecuter
   */
  public void removeClient(Client client);
  
  /**
   * <p>This is used to add a {@link Server} to a SocketExecuter.  Once its added the {@link Server} can begin accepting 
   * and processing new connections to it.</p>
   * 
   * @param server adds a {@link Server} to this SocketExecuter.
   */
  public void addServer(Server server);
  
  /**
   * <p>This is used to remove {@link Server} from a SocketExecuter.  Once a server is removed it will
   * no longer process new connections.  It is important to note removing a {@link Server} does not close any
   * listen ports, you must {@link Server#close()} on the {@link Server} to do that.  Calling {@link Server#close()} will
   * automatically remove the Server from the SocketExecuter.</p>
   * 
   * @param server removes a {@link Server} from the SocketExecuter
   */
  public void removeServer(Server server);

  /**
   * <p>Flags a clients as having a newWrite pending. The {@link Client} must already be added via {@link #addClient(Client)}.  
   * This notify the SocketExecuter to check it to see if the {@link Client} can write. This should only be called if the 
   * {@link Client} is transitioning from a non-write state to a write state.</p>  
   * 
   * <p>Generally this is only used internally by a {@link Client}
   * and unless otherwise stated should not need to be done unless implementing your own {@link Client}.</p>
   * 
   * @param client the {@link Client} to flag for new write.
   */
  public void flagNewWrite(Client client);
  
  /**
   * <p>This will notify the SocketExecuter that the {@link Client} can now be read from.  
   * The {@link Client} will automatically be removed from the read operations if its maxBufferSize for
   * reads was hit.</p>
   *
   * <p>Generally this is only used internally by a {@link Client}
   * and unless otherwise stated should not need to be done unless implementing your own {@link Client}.</p>
   * 
   * @param client the {@link Client} to flag for new Read.
   */
  public void flagNewRead(Client client);
  
  /**
   * <p>Get the count of {@link Client} on this SocketExecuter.</p>
   * 
   * @return the number of clients.
   */
  public int getClientCount();
  
  /**
   * <p>Get the count of {@link Server} from the SocketExecuter.</p>
   * 
   * @return the number of Servers.
   */
  public int getServerCount();
  
  /**
   * <p>This returns the current {@link SimpleScheduler} for this SocketExecuter.
   * Every SocketExecuter must have some kind of a {@link SimpleScheduler} for it to 
   * execute client/server operations on.</p>
   * 
   * @return returns the {@link SimpleScheduler} the SocketExecuter is using.
   */
  public SimpleScheduler getThreadScheduler();
  
  /**
   * <p>This will give you read and write stats for the SocketExecuter.  This will tell you information about
   * the number of bytes sent/received by this SocketExecuter.</p>
   * 
   * @return a {@link SimpleByteStats} object to allow you to get the stats for this SocketExecuter.
   */
  public SimpleByteStats getStats();
  
  public void watchFuture(ListenableFuture<?> lf, long delay);
  
}
