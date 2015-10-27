package org.threadly.litesockets;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.Service;


/**
 * Basic SocketExecuter interface.  Different Implementations have different ways of operating, but
 * they must all implement this for clients/servers to operate against them.
 * 
 * @author lwahlmeier
 *
 */
public interface SocketExecuter extends Service {
  
  /**
   * This will create a UDPServer on the specified {@link SocketExecuter}.
   * 
   * Please note that UDPClients are made from UDPServers not directly on the {@link SocketExecuter} 
   * 
   * @param host The local host or IP the UDPServer should listen on.
   * @param port The local port the UDPServer should listen on.
   * @return A {@link UDPServer}.
   * @throws IOException This is only thrown if we can not create the UDPServers socket.
   */
  public UDPServer createUDPServer(String host, int port) throws IOException;
  
  /**
   * This will create a {@link TCPServer} on the specified {@link SocketExecuter}.
   * 
   * @param host The local host or IP the TCPServer should listen on.
   * @param port The local port the TCPServer should listen on.
   * @return a {@link TCPServer}.
   * @throws IOException This is only thrown if we can not create the TCPServers socket.
   */
  public TCPServer createTCPServer(String host, int port) throws IOException;
  
  /**
   * This will crate a {@link TCPServer} from a given {@link ServerSocketChannel} object.
   * 
   * @param ssc The {@link ServerSocketChannel} object to use for this TCPServer. 
   * @return a {@link TCPServer}.
   * @throws IOException This is thrown if there is a problem with the {@link ServerSocketChannel}. 
   */
  public TCPServer createTCPServer(ServerSocketChannel ssc) throws IOException;
  
  /**
   * This will create a {@link TCPClient} on the specified {@link SocketExecuter}.
   * 
   * @param host This is the remote host or IP to connect this client too.
   * @param port This is the remote port to connect this client too.
   * @return a {@link TCPClient} object.
   * @throws IOException This is thrown if we can not make the socket or can not connect to the remote host.
   */
  public TCPClient createTCPClient(String host, int port) throws IOException;
  
  /**
   * This will create a {@link TCPClient} on the specified {@link SocketExecuter} with
   * an already created {@link SocketChannel}.  This is generally how a TCPServer will create its TCPClients.
   * 
   * @param sc the {@link SocketChannel} to be used for this TCPClient.
   * @return a {@link TCPClient}
   * @throws IOException This is thrown if there is a problem with the passed in {@link SocketChannel}.
   */
  public TCPClient createTCPClient(SocketChannel sc) throws IOException;
  
  /**
   * This allows you to get the {@link Executor} for a specified object.
   * 
   * @param obj The Client whose {@link Executor} you are looking for
   * @return the {@link Executor} for that object.
   */
  public Executor getExecutorFor(Client obj);
  
  /**
   * This is called when the a clients state needs to be rechecked.  It will cause the SocketExecuter to 
   * start checking the client for any state changes that are currently allowed.  The {@link Client} 
   * must have been created using this SocketExecuter or from a Server that was created with it.
   * 
   * @param client The {@link Client} object to check the state of.
   */
  public void setClientOperations(Client client);
  
  /**
   * This is used by {@link Server}  to tell the SocketExecuter to start allowing new clients to be accepted.
   * The {@link Server} must have been created with this SocketExecuter.
   * 
   * @param server The {@link Server} to start listening on.
   */
  public void startListening(Server server);
  
  /**
   * This will stop tell the executer to stop listening for new connections on the specified {@link Server}.
   * The {@link Server} will not be closed, but will no longer accept or call back in new connections till startListening 
   * is called for it.
   * 
   * @param server The {@link Server} to stop listening with.
   */
  public void stopListening(Server server);
  
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
   * <p>This returns the current {@link SubmitterScheduler} for this SocketExecuter.
   * Every SocketExecuter must have some kind of a {@link SubmitterScheduler} for it to 
   * execute client/server operations on.</p>
   * 
   * @return returns the {@link SubmitterScheduler} the SocketExecuter is using.
   */
  public SubmitterScheduler getThreadScheduler();
  
  /**
   * <p>This will give you read and write stats for the SocketExecuter.  This will tell you information about
   * the number of bytes sent/received by this SocketExecuter.</p>
   * 
   * @return a {@link SimpleByteStats} object to allow you to get the stats for this SocketExecuter.
   */
  public SimpleByteStats getStats();
  
  /**
   * <p>This allows you to put a timer on a {@link ListenableFuture}.  If the timer triggers before the  
   * {@link ListenableFuture} is done it will cancel the {@link ListenableFuture}</p>
   * 
   * @param lf The {@link ListenableFuture} to watch.
   * @param delay The delay time in Millis to wait for the {@link ListenableFuture} to finish.
   */
  public void watchFuture(ListenableFuture<?> lf, long delay);
}
