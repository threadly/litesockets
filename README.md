

# litesockets
Light weight socket library which relies heavily on threadly concurrency tools.

Made for highly concurrent applications generally more server side though would more then work for clients.

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

There are 3 different ways to write on the client.  Clients have a "Buffer" size that they try to stay under.  

-  .writeTry(ByteBuffer) will tell you if it could not complete the write because the buffer is already maxed out.  
-  .writeBlocking(ByteBuffer) will block until it can do this write.  Depending on how threading is setup you might need to be careful using this, especially if you are connecting to yourself in the same process(deadlock possibilities).
-  .writeForce(ByteBuffer) this will force the client to add the write even if it goes over the max buffer size.  This should be used only when you know what your are doing as if you just kept doing it and nothing was ever written to the socket you will eventually overfill memory.

writes are ByteBuffers only which means they are byte arrays or chunks of data.  In general you should send complete protocol packets at once as the ordering if write is called from different threads is not guaranteed.  If you need to do something like stream a large file your own locking/blocking of the writes might be needed.



## Simple Client Example

```java

    final String GET = "GET / HTTP/1.1\r\nUser-Agent: litesockets/3.3.0\r\nHost: www.google.com\r\nAccept: */*\r\n\r\n";

    //This is the SocketExecuter this runs the selector and adds reads/writes to the clients
    //as well as runs the call backs 
    final ThreadedSocketExecuter TSE = new ThreadedSocketExecuter();
    TSE.start();
    
    //MergedByteBuffers class allows you to put many ByteBuffers into one object
    //To allow you to deal with them in a combined way
    final MergedByteBuffers mbb = new MergedByteBuffers();
    
    //This makes a connection to the specified host/port
    //As the connection is made at this point no reads or writes will be 
    //pulled off the socket yet.
    final TCPClient client = new TCPClient("www.google.com", 80);
    
    //Here we set the Reader call back.  Everytime the client gets a read
    //This will be executed.  This will happen in a single threaded way per client.
    //Because it returns the client this is for you could have 1 Reader for many clients
    //assuming you are watching threading between then on your own.
    client.setReader(new Reader() {
        @Override
        public void onRead(Client returnedClient) {
            mbb.add(returnedClient.getRead());
            System.out.println(mbb.getAsString(mbb.remaining()));
        }
    });
    
    //Add the client to the SocketExecuter.  At this point Reads and Writes will start
    //going to and from the client object.  As long as a Reader has been set you could
    //start getting callbacks at this point.
    TSE.addClient(client);
    
    //We tell the client to write data to the socket.  Since this is to an http server we send
    //a simple GET request once the server gets that it will send us the response.
    client.writeBlocking(ByteBuffer.wrap(GET.getBytes()));
    
    //A real app would not need to sleep here but would need to be kept from exiting some how
    Thread.sleep(5000);

```

##Simple Server Example

```java
    final String hello = "hello";

    //We use a concurrentMap since the Servers Accept callback can happen on any thread in the threadpool
    final ConcurrentHashMap<Client, MergedByteBuffers> clients = new ConcurrentHashMap<Client, MergedByteBuffers>();

    //This is the SocketExecuter this runs the selector and adds reads/writes to the clients
    //as well as runs the call backs.  By default this is a singleThreadPool, a threadpool
    //Can be passed in if more threads are needed.
    final ThreadedSocketExecuter TSE = new ThreadedSocketExecuter();
    TSE.start();

    //We create a listen socket here.  The socket is opened but nothing can be accepted
    //Untill we add it to the SocketExecuter.
    TCPServer server = new TCPServer("localhost", 5555);

    //Here we set the ClientAcceptor callback.  This is what is called when a new client connects to the server.
    server.setClientAcceptor(new ClientAcceptor() {
      @Override
      public void accept(final Client newClient) {
        //Here we set the new clients Reader which adds new Reads to a MergedByteBuffer
        //This callback for the client will happen in a single threaded manor.
        final TCPClient tc = (TCPClient)newClient;
        tc.setReader(new Reader() {
          @Override
          public void onRead(Client client) {
            clients.get(client).add(client.getRead());
          }});
        //Here we set the closer for the client.  This will be called only once when the socket is closed.
        //This also happens in a single threaded manor and should be after all the reads are processed for the client.
        tc.setCloser(new Closer() {
          @Override
          public void onClose(Client client) {
            MergedByteBuffers mbb = clients.remove(client);
            if(mbb.remaining() > 0) {
              //If the client sent something write it to stdout once it closed
              System.out.println("Client Wrote:"+mbb.getAsString(mbb.remaining()));
            }
          }});

        //Add the client to the map.  Must do this before we add to the TSE or
        //We would get NPE when looking it up in the map.
        clients.put(newClient, new MergedByteBuffers());

        //Add the client to the SocketExecuter (we can get reads at this point.
        TSE.addClient(newClient);

        //Write hello to the socket.  The forceWrite will finish the write to the client
        //object w/o caring about buffer size.  The Socket executer will deal with getting it
        //onto the socket.  This could be used with caution as you could over fill memory if you do this to fast.
        newClient.writeForce(ByteBuffer.wrap(hello.getBytes()));

        //Once the client is fully setup we schedule a close on the client for 2 seconds out.
        TSE.getThreadScheduler().schedule(new Runnable() {
          @Override
          public void run() {
            newClient.close();
          }}, 2000);
     }});

    //Here we add the server. At this point we can accept client connections.
    TSE.addServer(server);

    //A real server would not sleep here.
    Thread.sleep(10000);

```



