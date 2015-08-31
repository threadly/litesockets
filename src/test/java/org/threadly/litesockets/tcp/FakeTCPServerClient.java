package org.threadly.litesockets.tcp;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Closer;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.Server.ServerCloser;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.utils.MergedByteBuffers;

public class FakeTCPServerClient implements Reader, Closer, ClientAcceptor, ServerCloser{
  public SocketExecuter se;
  public ConcurrentHashMap<Client, MergedByteBuffers> map = new ConcurrentHashMap<Client, MergedByteBuffers>();
  public ArrayList<Client> clients = new ArrayList<Client>();
  public ArrayList<Server> servers = new ArrayList<Server>();

  public FakeTCPServerClient(SocketExecuter se) {
    this.se = se;
  }

  @Override
  public void onRead(Client client) {
    map.putIfAbsent(client, new MergedByteBuffers());
    MergedByteBuffers mbb = client.getRead();
    System.out.println("GotData:"+mbb.remaining()+":"+client);
    map.get(client).add(mbb);
  }

  @Override
  public void onClose(Client client) {
    System.out.println("Closed!");
    map.remove(client);
  }

  @Override
  public void onClose(Server server) {
    System.out.println("Server Closed!!");
  }

  @Override
  public void accept(Client sc) {

    addTCPClient(sc);
  }
  
  public void addTCPServer(Server server) {
    servers.add(server);
    server.setCloser(this);
    server.setClientAcceptor(this);
    server.start();
  }
  
  public void addTCPClient(Client client) {
    MergedByteBuffers mbb = map.putIfAbsent(client, new MergedByteBuffers());
    clients.add(client);
    System.out.println("Accepted new Client!:"+map.size()+":"+client+":"+mbb);
    client.setReader(this);
    client.setCloser(this);
    if(client instanceof SSLClient) {
      SSLClient sslc = (SSLClient)client;
      sslc.doHandShake();
      sslc.getTCPClient().connect();
    } else {
      client.connect();      
    }
  }

}
