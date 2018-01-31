# litesockets
Light weight socket library which relies heavily on threadly concurrency tools.

Made for highly concurrent applications generally more server side though would more then work for clients.

Include the litesockets library into your project from maven central: 

```script
<dependency>
	<groupId>org.threadly</groupId>
	<artifactId>litesockets</artifactId>
	<version>4.3</version>
</dependency>
```

## Concepts

The main concept behind litesockets is that every "Client" should be treaded in a single threaded manor.  Not that they cant farm off tasks in a multiThreaded way at some point, but all reading and writing to the socket ~~should~~ must occour in a single threaded manor so that the order of the packets from the wire is guaranteed.

Server objects are different.  In general the process for accepting the clients from the socket does happen single threaded, but once the basic socket accept has been done we can move any more processing to the threadPool

## Components

### SocketExecuter

The SocketExecuter is the class that does the socket work.  Its general function is pretty simple when the client can Read or Write do that, and when the server can Accept do that also.  Its responsible for firing off the correct callbacks with the correct threading once that is done.

Many different SocketExecuters for many different work load could be created.

### Server

The server object is used for normal server type operations.  Anything that **listens** for new connections but does not read or write itself is considered a Server.  Server objects require that you set a ClientAcceptor call back.  This is what is called when a new client is accepted by the Server.

This gets a little fuzzy in the case of UDP.  A UDP server technically does not "accept" new clients.  This is faked in litesockets by seeing each new host/port connection as a new "Client".  Because the UDP Server socket does the read/write adding a UDP client to the SocketExecuter is a noop.

### Client

The client is what reads/writes to the socket.  This represents a connection to a spesific host/port pair.
All Read operations happen in a single threaded manor when the Reader callback is called.  Everytime the Reader is called you must call .getRead() on the client.  The client can also have a Closer set.  It will let you know when that client has closed its connection.  The Close should generally only come after all the Reads are done. 

writes are ByteBuffers only which means they are byte arrays or chunks of data.  In general you should send complete protocol packets at once as the ordering if write is called from different threads is not guaranteed.  If you need to do something like stream a large file your own locking/blocking of the writes might be needed.


## Simple Client Example

```java

    final String GET = "GET / HTTP/1.1\r\nUser-Agent: litesockets/3.3.0\r\nHost: www.google.com\r\nAccept: */*\r\n\r\n";
    final SettableListenableFuture<Object> onReadFuture = new SettableListenableFuture<Object>(false);
    //This is the SocketExecuter this runs the selector and adds reads/writes to the clients
    //as well as runs the call backs 
    final ThreadedSocketExecuter TSE = new ThreadedSocketExecuter();
    TSE.start();
    
    //MergedByteBuffers class allows you to put many ByteBuffers into one object
    //To allow you to deal with them in a combined way
    final MergedByteBuffers mbb = new MergedByteBuffers();
    
    //This makes a connection to the specified host/port
    //The connection is not yet actually made, connect() must be called to do that.
    final TCPClient client = TSE.createTCPClient("www.google.com", 80);
    
    //Here we set the Reader call back.  Everytime the client gets a read
    //This will be executed.  This will happen in a single threaded way per client.
    //Because it returns the client that the read happened on you can have 1 Reader for many clients
    //assuming you are watching threading between then on your own.
    client.setReader(new Reader() {
        @Override
        public void onRead(Client returnedClient) {
            mbb.add(returnedClient.getRead());
            System.out.println(mbb.getAsString(mbb.remaining()));
            onReadFuture.setResult("");
        }
    });
    
    ListenableFuture<?> lf = client.connect();
    lf.get();
    
    //We tell the client to write data to the socket.  Since this is to an http server we send
    //a simple GET request once the server gets that it will send us the response.
    ListenableFuture<?> wlf = client.write(ByteBuffer.wrap(GET.getBytes()));
    //Every write returns a future that will be completed once the write has been handed off to the OS.
    
    //Wait till we get a response back then continue;
    onReadFuture.get();

```

##Echo Server Example

```java
    final String hello = "hello\n";
    final String echo = "ECHO: ";
    final SettableListenableFuture<Object> exitSent = new SettableListenableFuture<Object>(false); 
    
    //We use a concurrentMap since the Servers Accept callback can happen on any thread in the threadpool
    final ConcurrentHashMap<Client, MergedByteBuffers> clients = new ConcurrentHashMap<Client, MergedByteBuffers>();

    //This is the SocketExecuter this runs the selector and adds reads/writes to the clients
    //as well as runs the call backs.  By default this is a singleThreadPool, a threadpool
    //Can be passed in if more threads are needed.
    final ThreadedSocketExecuter TSE = new ThreadedSocketExecuter();
    TSE.start();

    //We create a listen socket here.  The socket is opened but nothing can be accepted
    //Untill we run start on it.
    TCPServer server = TSE.createTCPServer("localhost", 5555);

    //Here we set the ClientAcceptor callback.  This is what is called when a new client connects to the server.
    server.setClientAcceptor(new ClientAcceptor() {
      @Override
      public void accept(final Client newClient) {
        final TCPClient tc = (TCPClient)newClient;
        
        //Add the client to the map with a queue
        clients.put(newClient, new MergedByteBuffers());
        
        //Set the clients reader, any data sent before it added will be called as soon as we add the reader.        
        tc.setReader(new Reader() {
          @Override
          public void onRead(Client client) {
            MergedByteBuffers mbb = client.getRead();
            if(mbb.indexOf("exit") > -1) {
              exitSent.setResult("");
            } else {
              //We just assume everything is a string 
              String str = mbb.getAsString(mbb.remaining());
              clients.get(client).add(ByteBuffer.wrap(str.getBytes()));
              client.write(ByteBuffer.wrap((echo+str).getBytes()));
            }
          }});
        //Here we set the closer for the client.  This will be called only once when the socket is closed.
        //This also happens in a single threaded manor and should be after all the reads are processed for the client.
        tc.setCloser(new Closer() {
          @Override
          public void onClose(Client client) {
            //Normally you would want to clean up client state here, but we save everything for this servers exit.
            //MergedByteBuffers mbb = clients.remove(client);
            if(mbb.remaining() > 0) {
              //If the client sent something write it to stdout once it closed
              System.out.println("Client Wrote:"+mbb.getAsString(mbb.remaining()));
            }
          }});

        //Write hello to client.  The Socket executer will deal with getting it
        //onto the actual socket.
        newClient.write(ByteBuffer.wrap(hello.getBytes()));
     }});

    //Here we add the server. At this point we can accept client connections.
    server.start();

    //Wait for an exit from the client.
    exitSent.get();
    
    //Print out every client connected and what they sent
    for(Client client: clients.keySet()) {
     MergedByteBuffers mbb = clients.get(client);
      System.out.println("Client:"+client+":\n\tsent data:\n"+mbb.getAsString(mbb.remaining()));
      System.out.println("--------------------");
    }

```



