package org.threadly.litesockets.udp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Closer;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.Server.ServerCloser;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.utils.MergedByteBuffers;

public class FakeUDPServerClient implements Closer, Reader, ClientAcceptor, ServerCloser {
  SocketExecuterInterface SE;
  Set<UDPServer> servers = new HashSet<UDPServer>();
  ConcurrentHashMap<UDPClient, MergedByteBuffers> clients = new ConcurrentHashMap<UDPClient, MergedByteBuffers>();
  List<UDPClient> clientList = new ArrayList<UDPClient>();
  
  public FakeUDPServerClient(SocketExecuterInterface se) {
    SE = se;
  }
  
  public void AddUDPServer(UDPServer userver) {
    servers.add(userver);
    userver.setClientAcceptor(this);
    userver.setCloser(this);
    SE.addServer(userver);
  }

  @Override
  public void accept(Client c) {
    UDPClient uc = (UDPClient) c;
    uc.setReader(this);
    uc.setCloser(this);
    clients.put(uc, new MergedByteBuffers());
    clientList.add(uc);
  }

  @Override
  public void onRead(Client client) {
    ByteBuffer bb = client.getRead();
    System.out.println("Got Read:"+bb);
    MergedByteBuffers mbb = clients.get(client);
    if(mbb != null) {
      mbb.add(bb);
    }
  }

  @Override
  public void onClose(Client client) {
    System.out.println("Closing:"+client);
    clients.remove(client);
  }

  @Override
  public void onClose(Server server) {
    System.out.println("Close Server:"+server);
    servers.remove(server);
  }

}
