package org.threadly.litesockets.tcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.CloseListener;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.Server.ServerCloseListener;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.TCPClient;
import org.threadly.litesockets.TCPServer;

public class FakeTCPServerClient implements Reader, CloseListener, ClientAcceptor, ServerCloseListener{
  private ConcurrentHashMap<TCPClient, MergedByteBuffers> map = new ConcurrentHashMap<TCPClient, MergedByteBuffers>();
  private ArrayList<TCPClient> clients = new ArrayList<TCPClient>();
  private ArrayList<TCPServer> servers = new ArrayList<TCPServer>();

  public FakeTCPServerClient() {
  }
  
  public int getNumberOfClients() {
    synchronized(clients) {
      return clients.size();
    }
  }
  
  public TCPClient getClientAt(int inx) {
    synchronized(clients) {
      return clients.get(inx);
    }
  }
  
  public List<TCPClient> getAllClients() {
    synchronized(clients) {
      return new ArrayList<TCPClient>(clients);
    }
  }
  
  public MergedByteBuffers getClientsBuffer(TCPClient client) {
    return map.get(client);
  }
  
  public List<TCPServer> getAllServers() {
    synchronized(servers) {
      return new ArrayList<TCPServer>(servers);
    }
  }

  @Override
  public void onRead(Client client) {
    MergedByteBuffers mbb = client.getRead();
    System.out.println("GotData:"+mbb.remaining()+":"+client);
    map.get(client).add(mbb);
  }

  @Override
  public void onClose(Client client) {
    System.out.println("Closed!");
    map.remove((TCPClient)client);
    synchronized(clients) {
      clients.remove((TCPClient) client);
    }
  }

  @Override
  public void onClose(Server server) {
    System.out.println("Server Closed!!");
    synchronized(servers) {
      servers.remove(server);
    }
  }

  @Override
  public void accept(Client sc) {
    addTCPClient(sc);
  }
  
  public void addTCPServer(Server server) {
    synchronized(servers) {
      servers.add((TCPServer)server);
    }
    server.addCloseListener(this);
    server.setClientAcceptor(this);
    server.start();
  }
  
  public void addTCPClient(Client client) {
    TCPClient tclient = (TCPClient)client;
    map.put(tclient, new ReuseableMergedByteBuffers());
    synchronized(clients) {
      clients.add(tclient);
    }
    System.out.println("Accepted new Client!:"+map.size()+":"+client+":");
    client.setReader(this);
    client.addCloseListener(this);
    client.connect();
//    if(client instanceof SSLProcessor) {
//      SSLProcessor sslc = (SSLProcessor)client;
//      sslc.doHandShake();
//      sslc.getTCPClient().connect();
//    } else {
//      
//    }
  }

}
