package org.threadly.litesockets.tcp;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.FutureCallback;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.util.ExceptionUtils;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 */
public class SSLClient extends TCPClient {
  public static final int EXTRA_BUFFER_AMOUNT = 50;
  public static final int PREALLOCATE_BUFFER_MULTIPLIER = 3;
  private final AtomicBoolean finishedHandshake = new AtomicBoolean(false);
  private final AtomicBoolean startedHandshake = new AtomicBoolean(false);
  private final boolean connectHandshake;
  private final MergedByteBuffers tmpWriteBuffers = new MergedByteBuffers();
  private final MergedByteBuffers decryptedReadList = new MergedByteBuffers();
  private final Reader classReader = new SSLReader();
  private final SettableListenableFuture<SSLSession> handshakeFuture = new SettableListenableFuture<SSLSession>(false);
  private final SSLEngine ssle;
  private final ByteBuffer encryptedReadBuffer;

  private volatile Reader sslReader;
  private ByteBuffer writeBuffer;
  
  private ByteBuffer decryptedReadBuffer;
  
   
  /**
   * <p>This is a simple SSLConstructor.  It uses the default socket timeout, and very insecure cert
   * checking.  This setup to generally connect to any server, and does not validate anything.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   */
  public SSLClient(String host, int port) {
    this(host, port, SSLUtils.OPEN_SSL_CTX.createSSLEngine(host, port));
  }

  /**
   * <p>This simple SSLConstructor.  It allows you to specify the SSLEngine to use to allow you to
   * validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @param ssle The SSLEngine to use for the connection.
   */
  public SSLClient(String host, int port, SSLEngine ssle) {
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT);
  }

  /**
   * <p>This constructor allows you to specify the SSLEngine to use to
   * validate the server certs with the parameters you decide.  
   * It also allows you to set the timeout values.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @param ssle The SSLEngine to use for the connection.
   * @param timeout This is the connection timeout.  It is used for the actual connection timeout and separately for the SSLHandshake.
   */
  public SSLClient(String host, int port, SSLEngine ssle, int timeout){
    this(host, port, ssle, TCPClient.DEFAULT_SOCKET_TIMEOUT, true);
  }

  /**
   * <p>This constructor allows you to specify the SSLEngine to use to allow you to
   * validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param host The host or IP to connect to.
   * @param port The port on the host to connect to.
   * @param ssle The SSLEngine to use for the connection.
   * @param timeout This is the connection timeout.  It is used for the actual connection timeout and separately for the SSLHandshake.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   */
  public SSLClient(String host, int port, SSLEngine ssle, int timeout, boolean doHandshake){
    super(host, port, timeout);
    this.ssle = ssle;
    ssle.setUseClientMode(true);
    connectHandshake = doHandshake;
    super.setReader(classReader);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT);
  }

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the SSLEngine 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param client The SocketChannel to use for the SSLClient.
   * @param ssle The SSLEngine to use for the connection.
   * @param clientSSL Set this to true if this is a client side connection, false if its server side.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL) throws IOException {
    this(client, ssle, clientSSL, true);
  }

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the SSLEngine 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param client The SocketChannel to use for the SSLClient.
   * @param ssle The SSLEngine to use for the connection.
   * @param clientSSL Set this to true if this is a client side connection, false if its server side.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(SocketChannel client, SSLEngine ssle, boolean clientSSL, boolean doHandshake) throws IOException {
    super(client);
    this.ssle = ssle;
    this.connectHandshake = doHandshake;
    ssle.setUseClientMode(clientSSL);
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(classReader);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT);
  }

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the SSLEngine 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   * 
   * <p>Because we are making the connection this is always a "ClientSide" connection.</p>
   *  
   * @param client The TCPClient to use for the SSLClient.
   * @param ssle The SSLEngine to use for the connection.
   * @param clientSSL Set this to true if this is a client side connection, false if its server side.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @throws IOException An IOException is thrown if there is a failure to connect for any reason.
   */
  public SSLClient(TCPClient client, SSLEngine ssle, boolean clientSSL, boolean doHandshake) throws IOException {
    super(client.getChannel());
    if(client.getReadBufferSize() > 0 || client.getWriteBufferSize() > 0) {
      throw new IllegalStateException("Can not add a TCPClient with pending Reads or Writes!");
    }
    if(client.isClosed()) {
      throw new IllegalStateException("Can not add closed TCPClient to sslConstructor");
    }
    startedConnection.set(true);
    client.markClosed();
    setCloser(client.getCloser());
    setReader(client.getReader());
    this.ssle = ssle;
    ssle.setUseClientMode(clientSSL);
    this.connectHandshake = doHandshake;
    if(doHandshake) {
      doHandShake();
    }
    super.setReader(classReader);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT);
  }
  
  @Override
  public ListenableFuture<Boolean> connect(){
    super.connect();
    if(connectHandshake) {
      doHandShake();
      connectionFuture.addCallback(new FutureCallback<Boolean>() {
        @Override
        public void handleResult(Boolean result) {
        }
        @Override
        public void handleFailure(Throwable t) {
          handshakeFuture.setFailure(t);
        }});
    }
    return connectionFuture;
  }

  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()+EXTRA_BUFFER_AMOUNT) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()*PREALLOCATE_BUFFER_MULTIPLIER);
    }
    return decryptedReadBuffer;
  }

  /**
   * This lets you know if the connection is currently encrypted or not.
   * @return true if the connection is encrypted false if not.
   */
  public boolean isEncrypted() {
    if(startedHandshake.get() && ssle.getSession().getProtocol().equals("NONE")) {
      return false;
    }
    return true;
  }

  /**
   * <p>If doHandshake was set to false in the constructor you can start the handshake by calling this method.
   * The client will not start the handshake till its added to a SocketExecuter.  The future allows you to know
   * when the handshake has finished if if there was an error.  While the handshake is processing all writes to the 
   * socket will queue.</p>
   * 
   * @return A ListenableFuture.  If a result was given it succeeded, if there is an error it failed.  The connection is closed on failures.
   */
  public ListenableFuture<SSLSession> doHandShake() {
    if(startedHandshake.compareAndSet(false, true)) {
      try {
        ssle.beginHandshake();
      } catch (SSLException e) {
        this.handshakeFuture.setFailure(e);
      }
      if(ssle.getHandshakeStatus() == NEED_WRAP) {
        writeForce(ByteBuffer.allocate(0));
      }
      if(this.seb != null) {
        seb.getThreadScheduler().schedule(new Runnable() {
          @Override
          public void run() {
            if(! handshakeFuture.isDone() && 
               handshakeFuture.setFailure(new TimeoutException("Timed out doing SSLHandshake!!!"))) {
              close();
            }
          }}, maxConnectionTime);
      }
    }
    return handshakeFuture;
  }

  @Override
  protected void setClientsSocketExecuter(SocketExecuterInterface sei) {
    super.setClientsSocketExecuter(sei);
    if(startedHandshake.get() && !handshakeFuture.isDone()) {
      sei.getThreadScheduler().schedule(new Runnable() {
        @Override
        public void run() {
          if(! handshakeFuture.isDone() && 
             handshakeFuture.setFailure(new TimeoutException("Timed out doing SSLHandshake!!!"))) {
            close();
          }
        }}, maxConnectionTime);
    }
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
    if(this.writeBuffer == null || this.writeBuffer.remaining() < ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT) {
      this.writeBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()*PREALLOCATE_BUFFER_MULTIPLIER);
    }
    return writeBuffer;
  }
  
  @Override
  public int getWriteBufferSize() {
    return super.getWriteBufferSize() + tmpWriteBuffers.remaining();
  }

  @Override
  public void writeForce(ByteBuffer buffer) {
    if (!this.isClosed() && startedHandshake.get()) {
      if(!finishedHandshake.get()&& buffer.remaining() != 0) {
        synchronized(tmpWriteBuffers) {
          if(!finishedHandshake.get()) {
            this.tmpWriteBuffers.add(buffer);
            return;
          }
        }
      } else if(finishedHandshake.get() && buffer.remaining() == 0) {
        synchronized(tmpWriteBuffers) {
          if(tmpWriteBuffers.remaining() > 0) {
            buffer = tmpWriteBuffers.pull(tmpWriteBuffers.remaining());
          }
        }
      }
      synchronized(ssle) {
        boolean gotFinished = false;
        ByteBuffer oldBB = buffer.duplicate();
        ByteBuffer newBB; 
        ByteBuffer tmpBB;
        while (ssle.getHandshakeStatus() == NEED_WRAP || oldBB.remaining() > 0) {
          newBB = getAppWriteBuffer();
          try {
            tmpBB = newBB.duplicate();
            SSLEngineResult res = ssle.wrap(oldBB, newBB);

            if(res.getHandshakeStatus() == FINISHED) {
              gotFinished = true;
            } else {
              while (ssle.getHandshakeStatus() == NEED_TASK) {
                runTasks();
              }
            }
            if(tmpBB.hasRemaining()) {
              tmpBB.limit(newBB.position());
              super.writeForce(tmpBB);
            }
          } catch(Exception e) {
            if(! handshakeFuture.isDone() && 
               handshakeFuture.setFailure(e)) {
              this.close();
            }
            break;
          }
        }
        if(gotFinished) {
          if(finishedHandshake.compareAndSet(false, true)) {
            writeForce(ByteBuffer.allocate(0));
            handshakeFuture.setResult(ssle.getSession());
          }
        }
      }
    } else if(!isClosed() && !startedHandshake.get()){
      super.writeForce(buffer);
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

  private void doRead() {
    MergedByteBuffers clientBuffer = super.getRead();
    if(this.startedHandshake.get()) {
      try {
        while(clientBuffer.remaining() > 0) {
          if(clientBuffer.remaining() > encryptedReadBuffer.remaining()) {
            byte[] ba = new byte[encryptedReadBuffer.remaining()];
            clientBuffer.get(ba);
            encryptedReadBuffer.put(ba);
          } else {
            encryptedReadBuffer.put(clientBuffer.pop());
          }
          while(encryptedReadBuffer.position() > 0) {
            encryptedReadBuffer.flip();
            ByteBuffer dbb = getDecryptedByteBuffer();
            ByteBuffer newBB = dbb.duplicate();
            SSLEngineResult res = ssle.unwrap(encryptedReadBuffer, dbb);
            //We have to check both each time till complete
            if(! handshakeFuture.isDone()) {
              processHandshake(res.getHandshakeStatus());
              processHandshake(ssle.getHandshakeStatus());
            }

            newBB.limit(dbb.position());
            encryptedReadBuffer.compact();
            if(newBB.hasRemaining()) {
              Reader lreader = sslReader;
              if(lreader != null) {
                //Sync not needed here, this is only accessed by ReadThread.
                decryptedReadList.add(newBB);
                lreader.onRead(this);
              }
            } else if (res.getStatus() == Status.BUFFER_UNDERFLOW || encryptedReadBuffer.remaining() == 0){
              break;
            }
          }
        }
      } catch (SSLException e) {
        handshakeFuture.setFailure(e);
        ExceptionUtils.handleException(e);
        this.close();
      }
    } else {
      decryptedReadList.add(clientBuffer);
      sslReader.onRead(this);
    }
  }

  private void processHandshake(HandshakeStatus status) {
    switch(status) {
    case FINISHED: {
      synchronized(tmpWriteBuffers) {
        if(this.finishedHandshake.compareAndSet(false, true)){
          writeForce(ByteBuffer.allocate(0));
          handshakeFuture.setResult(ssle.getSession());
        }
      }
    } break;
    case NEED_TASK: {
      while (ssle.getHandshakeStatus() == NEED_TASK) {
        runTasks();
      }
    } break;
    case NEED_WRAP: {
      writeForce(ByteBuffer.allocate(0));
    } break;
    case NOT_HANDSHAKING:
    default: {

    } break;

    }
  }
  
  /**
   * Reader for SSLClient.  This is used to read from the TCPClient into the SSLClient. 
   */
  private class SSLReader implements Reader {
    @Override
    public void onRead(Client client) {
      doRead();
    }
  }
}
