package org.threadly.litesockets.tcp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Closer;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.Server.ServerCloser;
import org.threadly.litesockets.SocketExecuterBase;
import org.threadly.litesockets.utils.MergedByteBuffers;

public class FakeTCPServerClient implements Reader, Closer, ClientAcceptor, ServerCloser{
  public SocketExecuterBase se;
  public ConcurrentHashMap<Client, MergedByteBuffers> map = new ConcurrentHashMap<Client, MergedByteBuffers>();
  public ArrayList<Client> clients = new ArrayList<Client>();
  public ArrayList<Server> servers = new ArrayList<Server>();

  public FakeTCPServerClient(SocketExecuterBase se) {
    this.se = se;
  }

  @Override
  public void onRead(Client client) {
    map.putIfAbsent(client, new MergedByteBuffers());
    ByteBuffer bb = client.getRead();
    System.out.println("GotData:"+bb+":"+client);
    map.get(client).add(bb);
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
    TCPClient client;
    client = (TCPClient)sc;
    if(sc instanceof SSLClient) {
      SSLClient sslc = (SSLClient)sc;
      sslc.doHandShake();
    }
    addTCPClient(client);
  }
  
  public void addTCPServer(TCPServer server) {
    servers.add(server);
    server.setCloser(this);
    server.setClientAcceptor(this);
    se.addServer(server);
  }
  
  public void addTCPClient(TCPClient client) {
    MergedByteBuffers mbb = map.putIfAbsent(client, new MergedByteBuffers());
    clients.add(client);
    System.out.println("Accepted new Client!:"+map.size()+":"+client+":"+mbb);
    client.setReader(this);
    client.setCloser(this);
    se.addClient(client);
  }

}
