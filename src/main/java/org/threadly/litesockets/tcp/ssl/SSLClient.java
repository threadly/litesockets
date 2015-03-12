package org.threadly.litesockets.tcp.ssl;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.tcp.TCPClient;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 *
 */
public class SSLClient extends TCPClient implements Reader{

  private final MergedByteBuffers decryptedReadList = new MergedByteBuffers();
  private final SSLEngine ssle;
  
  private ByteBuffer tmpAppBuffer;
  private ByteBuffer writeBuffer;
  
  private final ByteBuffer encryptedReadBuffer;
  private ByteBuffer decryptedReadBuffer;
  
  private volatile Reader sslReader; 

  public SSLClient(String host, int port) throws IOException {
    this(host, port, SSLUtils.OPEN_SSL_CTX.createSSLEngine(host, port));
  }
  
  public SSLClient(String host, int port, SSLEngine ssle) throws IOException {
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT);
  }

  public SSLClient(String host, int port, SSLEngine ssle, int timeout) throws IOException {
    super(host, port, timeout);
    this.ssle = ssle;
    ssle.setUseClientMode(true);
    doHandShake();
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
  }

  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL) throws IOException {
    super(client);
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    doHandShake();
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
  }

  public SSLClient(TCPClient client, SSLEngine ssle, boolean clientSSL) throws IOException {
    super(client.getChannel());
    if(client.getReadBufferSize() > 0 || client.getWriteBufferSize() > 0) {
      throw new IllegalStateException("Can not add a TCPClient with pending Reads or Writes!");
    }
    if(client.isClosed()) {
      throw new IllegalStateException("Can not add closed TCPClient to sslConstructor");
    }
    client.markClosed();
    setCloser(client.getCloser());
    setReader(client.getReader());
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    doHandShake();
    super.setReader(this);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
  }
  
  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()*1.5) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()*3);
    }
    return decryptedReadBuffer;
  }
  

  
  
  private void doHandShake() throws IOException {
    ByteBuffer appDataBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize());
    ByteBuffer netDataBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
    
    ByteBuffer eNetworkData = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize());
    ByteBuffer ePeerAppData = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize());
    
    //Start the handShake
    ssle.beginHandshake();
    SSLEngineResult.HandshakeStatus hs = ssle.getHandshakeStatus();
    Selector select = Selector.open();
    
    while (hs != FINISHED && hs != NOT_HANDSHAKING ) {
      if(Clock.lastKnownForwardProgressingMillis() - startTime > setTimeout) {
        throw new IOException("Timeout doing SSLHandshake!");
      }

      select.selectedKeys().clear();
      if(hs == NEED_UNWRAP) {
        channel.register(select, SelectionKey.OP_READ);
        select.select(100);
      } else if (hs == NEED_WRAP) {
        channel.register(select, SelectionKey.OP_WRITE);
        select.select(100);
      }
      channel.register(select, 0);

      while(hs == NEED_UNWRAP ) {
        hs = SSLUtils.doHandShakeRead(netDataBuffer, ePeerAppData, ssle, channel);
        if(hs == NEED_TASK) {
          runTasks();
          hs = ssle.getHandshakeStatus();
        }
      }
      while(hs ==  NEED_WRAP) {
        hs = SSLUtils.doHandShakeWrite(appDataBuffer, eNetworkData, ssle, channel);
        if(hs == NEED_TASK) {
          runTasks();
          hs = ssle.getHandshakeStatus();
        }
      }
    }
    select.close();
    tmpAppBuffer = appDataBuffer;
    tmpAppBuffer.clear();
  }
  
  private void runTasks() {
    SSLEngineResult.HandshakeStatus hs = ssle.getHandshakeStatus();
    while(hs == NEED_TASK) {
      Runnable task = ssle.getDelegatedTask();
      task.run();
      hs = ssle.getHandshakeStatus();
    }
  }
  
  private ByteBuffer getAppWriteBuffer() {
    if(this.writeBuffer == null || this.writeBuffer.remaining() < ssle.getSession().getPacketBufferSize()*1.5) {
      this.writeBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()*3);
    }
    return writeBuffer;
  }
  
  @Override
  public void writeForce(ByteBuffer buffer) {
    if (!this.isClosed() && buffer.hasRemaining()) {
      synchronized(ssle) {
        ByteBuffer oldBB = buffer.duplicate();
        ByteBuffer newBB; 
        ByteBuffer tmpBB;
        while (oldBB.remaining() > 0) {
          newBB = getAppWriteBuffer();
          try {
            tmpBB = newBB.duplicate();
            //TODO: we should probably check this for at least SSLCLOSE
            ssle.wrap(oldBB, newBB);
            tmpBB.limit(newBB.position());
            super.writeForce(tmpBB);
          } catch(Exception e) {
            break;
          }
        }
      }
    }
  }
  
  @Override
  public MergedByteBuffers getRead() {
    synchronized(decryptedReadList) {
      return decryptedReadList.duplicateAndClean();
    }
  }
  
  @Override
  public void setReader(Reader reader) {
    this.sslReader = reader;
  }

  @Override
  public void onRead(Client client) {
    try {
      MergedByteBuffers client_mbb = super.getRead();
      ByteBuffer client_buffer;
      while(client_mbb.remaining() > 0){
        client_buffer = client_mbb.pop();
        while(client_buffer.hasRemaining()) {
          if(client_buffer.remaining() > encryptedReadBuffer.remaining()) {
            byte[] ba = new byte[encryptedReadBuffer.remaining()];
            client_buffer.get(ba);
            encryptedReadBuffer.put(ba);
          } else {
            encryptedReadBuffer.put(client_buffer);
          }
          while(encryptedReadBuffer.position() > 0) {
            encryptedReadBuffer.flip();
            ByteBuffer dbb = getDecryptedByteBuffer();
            ByteBuffer newBB = dbb.duplicate();
            @SuppressWarnings("unused")
            SSLEngineResult res = ssle.unwrap(encryptedReadBuffer, dbb);
            newBB.limit(dbb.position());
            encryptedReadBuffer.compact();
            if(newBB.hasRemaining()) {
              if(sslReader != null) {
                synchronized(decryptedReadList) {
                  decryptedReadList.add(newBB);
                }
                sslReader.onRead(this);
              }
            } else {
              break;
            }
          }
        }
      }
    } catch (SSLException e) {
      ExceptionUtils.handleException(e);
      this.close();
    }
  }
  

}
