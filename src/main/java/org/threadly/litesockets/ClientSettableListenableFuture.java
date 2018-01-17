package org.threadly.litesockets;

import java.util.concurrent.Executor;

import org.threadly.concurrent.future.SettableListenableFuture;

/**
 * Implementation of {@link SettableListenableFuture} to access {@code protected} constructor where 
 * we can specify the executor the listener will complete on.  This is very useful / important 
 * for litesockets so that optimizations can occur when listeners complete on the client's executor.
 * 
 * @param <T> The type of result returned by this future
 */
public class ClientSettableListenableFuture<T> extends SettableListenableFuture<T> {
  // class and constructor needs to be public for `SSLProcessor`
  public ClientSettableListenableFuture(Client client) {
    this(client.getClientsThreadExecutor());
  }
  
  protected ClientSettableListenableFuture(Executor clientExecutor) {
    super(false, clientExecutor);
  }
}