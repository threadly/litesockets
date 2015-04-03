package org.threadly.litesockets;

import org.threadly.concurrent.SimpleSchedulerInterface;

public interface SocketExecuterInterface {
  /**
   * <p>Wire protocols supported by litesockets.  This is the protocol
   * used to communicate with. Depending on the protocol SocketExecuters might need to take a different action.</p>
   * 
   */
  public static enum WireProtocol {TCP, UDP}
  
  /**
   * <p>Add a client object to the SocketExecuter.  This will allow the client to read
   * and write data to its socket.</p>
   * 
   * @param client the client you are adding.
   */
  public void addClient(Client client);
  
  /**
   * <p>Remove a client object that has been added to the SocketExecuter.  Once this is done the client will
   * no longer be able to read/write to the socket.  If a write buffer for the client exists it might not be 
   * completely flushed out yet.  All reads should be at least queued in the Executer for a Read action.</p>
   * 
   * @param client the client object to remove from the SocketExecuter
   */
  public void removeClient(Client client);
  
  /**
   * <p>This is used to add a Server object to a SocketExecuter.  Once its added the Server object can begin accepting 
   * and processing new connections to it.</p>
   * 
   * @param server adds a server to the SocketExecuter.
   */
  public void addServer(Server server);
  
  /**
   * <p>This is used to remove Server objects from a SocketExecuter.  Once a server is removed it will
   * no longer process new connections.  It is important to note removing a server does not close any
   * listen ports, you must .close() on the server to do that.</p>
   * 
   * @param server removes a Server from the SocketExecuter
   */
  public void removeServer(Server server);

  /**
   * <p>Flags a clients as having a newWrite pending. The client must already be added via addClient().  This notify the 
   * WriteThread and check it to see if the client can write. This should only be called if the client is 
   * transitioning from a non-write state to a write state.</p>  
   * 
   * <p>Generally this is only used internally by a Client object
   * and unless otherwise stated should not need to be done unless implementing your own Client.</p>
   * 
   * @param client the Client object to flag for new write.
   */
  public void flagNewWrite(Client client);
  
  /**
   * <p>This will add the client to the ReadThread.  This can be called by the client
   * once it can Read again.  If we have ever read enough to fill the clients
   * Read Buffer and removed it from the ReadThread Thread this has to be called to add it back.</p>
   *
   * <p>Generally this is only used internally by a Client object
   * and unless otherwise stated should not need to be done unless implementing your own Client.</p>
   * 
   * @param client the Client object to flag for new Read.
   */
  public void flagNewRead(Client client);
  
  /**
   * <p>Get the count of clients in this SocketExecuter.</p>
   * 
   * @return the number of clients.
   */
  public int getClientCount();
  
  /**
   * <p>Get the count of servers from the SocketExecuter.</p>
   * 
   * @return the number of Servers.
   */
  public int getServerCount();
  
  /**
   * <p>This returns the current threadScheduler for this SocketExecuter.
   * Every SocketExecuter must have a threadScheduler for it to executer client/server
   * operations on.</p>
   * 
   * @return returns the threadScheduler the SocketExecuter is using.
   */
  public SimpleSchedulerInterface getThreadScheduler();
  
  /**
   * provided for {@link org.threadly.concurrent.AbstractService}
   */
  public void start();
  
  /**
   * provided for {@link org.threadly.concurrent.AbstractService}
   */
  public boolean startIfNotStarted();
  
  /**
   * provided for {@link org.threadly.concurrent.AbstractService}
   */
  public void stop();
  
  /**
   * provided for {@link org.threadly.concurrent.AbstractService}
   */
  public boolean stopIfRunning();
  
  /**
   * provided for {@link org.threadly.concurrent.AbstractService}
   */
  public boolean isRunning();

}
