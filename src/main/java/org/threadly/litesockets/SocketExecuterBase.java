package org.threadly.litesockets;

import org.threadly.concurrent.AbstractService;
import org.threadly.concurrent.SchedulerServiceInterface;

public abstract class SocketExecuterBase extends AbstractService {
  public static enum WireProtocol {TCP, UDP}
  
  /**
   * Add a client object to the SocketExecuter.  This will allow the client to read
   * and write data to its socket.
   * 
   * All SocketExecuters dont have to implement this (They can just throw instead), 
   * but if they dont Client objects can not be used by the 
   * SocketExecuter. 
   * 
   * @param client the client you are adding.
   */
  public abstract void addClient(Client client);
  
  /**
   * Remove a client object that has been added to the SocketExecuter.  Once this is done the client will
   * no longer be able to read/write to the socket.  If a write buffer for the client exists it might not be 
   * completely flushed out yet.  All reads should be at least queued for an action.
   * 
   * All SocketExecuters dont have to implement this (They can just throw instead), 
   * but if they dont Client objects can not be used by the 
   * SocketExecuter. 
   *  
   * @param client the client object to remove from the SocketExecuter
   */
  public abstract void removeClient(Client client);
  
  /**
   * This is used to add a Server object to a SocketExecuter.  Once its added the Server object can begin accepting 
   * and processing new connections to it.
   * 
   * All SocketExecuters dont have to implement this (They can just throw instead), 
   * but if they dont Server objects can not be used by the 
   * SocketExecuter. 
   * 
   * @param server adds a server to the SocketExecuter.
   */
  public abstract void addServer(Server server);
  
  /**
   * This is used to remove Server objects from a SocketExecuter.  Once a server is removed it will
   * no longer process new connections.  It is important to note removing a server does not close any
   * listen ports, you must .close() on the server to do that.
   * 
   * All SocketExecuters dont have to implement this (They can just throw instead), 
   * but if they dont Server objects can not be used by the 
   * SocketExecuter. 
   * 
   * @param server removes a Server from the SocketExecuter
   */
  public abstract void removeServer(Server server);
  
  /**
   * This is used to figure out if the current used thread is the SocketExecuters ReadThread.
   * This is used by clients to Enforce certain threads to do certain public tasks.
   * 
   * 
   * @return a boolean to tell you if the current thread is the readThread for this executer.
   */
  protected abstract boolean verifyReadThread();
  
  /**
   * Flags a clients as having a newWrite pending. This will add it to the 
   * WriteThread and check it to see if it can write.  This should only be called
   * if the client did not currently have a pending write.
   * 
   * @param client the Client object to flag for new write.
   */
  public abstract void flagNewWrite(Client client);
  
  /**
   * This will add the client to the ReadThread.  This can be called by the client
   * once it can Read again.  Basically if we have ever read enough to fill the clients
   * Read Buffer and removed it from the ReadThread Thread this has to be called to add it back.
   * 
   * @param client the Client object to flag for new Read.
   */
  public abstract void flagNewRead(Client client);
  
  /**
   * Get the count of clients in this SocketExecuter.
   * 
   * 
   * @return the number of clients.
   */
  public abstract int getClientCount();
  
  /**
   * Get the count of servers from the SocketExecuter.
   * 
   * @return the number of Servers.
   */
  public abstract int getServerCount();
  
  /**
   * This returns the current threadScheduler for this SocketExecuter.
   * Every SocketExecuter must have a threadScheduler for it to executer client/server
   * operations on.
   * 
   * @return returns the threadScheduler the SocketExecuter is using.
   */
  public abstract SchedulerServiceInterface getThreadScheduler();

}
