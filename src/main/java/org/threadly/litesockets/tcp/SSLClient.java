package org.threadly.litesockets.tcp;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.FutureCallback;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.WireProtocol;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.SimpleByteStats;
import org.threadly.util.ExceptionUtils;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 */
public class SSLClient extends Client {
  public static final int EXTRA_BUFFER_AMOUNT = 50;
  public static final int PREALLOCATE_BUFFER_MULTIPLIER = 3;
  
  private final TCPClient client;
  private final AtomicBoolean finishedHandshake = new AtomicBoolean(false);
  private final AtomicBoolean startedHandshake = new AtomicBoolean(false);
  private final boolean connectHandshake;
  private final MergedByteBuffers decryptedReadList = new MergedByteBuffers();
  private final Reader tcpReader = new SSLReader();
  private final Closer tcpCloser = new SSLCloser();
  private final SettableListenableFuture<SSLSession> handshakeFuture = new SettableListenableFuture<SSLSession>(false);
  private final SSLEngine ssle;
  private final ByteBuffer encryptedReadBuffer;

  private volatile Reader sslReader;
  protected volatile Closer sslCloser;
  private ByteBuffer writeBuffer;
  
  private ByteBuffer decryptedReadBuffer;
  
   
  /**
   * <p>This is a simple SSLConstructor.  It uses the default socket timeout, and very insecure cert
   * checking.  This setup to generally connect to any server, and does not validate anything.</p>
   * 
   * <p>This is always seen as a clientside connection</p>
   *  
   * @param client the {@link TCPClient} to wrap to do ssl/tls on.
   */
  public SSLClient(TCPClient client) {
    this(client, SSLUtils.OPEN_SSL_CTX.createSSLEngine(client.host, client.port));
  }

  /**
   * <p>This simple SSLConstructor.  It allows you to specify the SSLEngine to use to allow you to
   * validate the servers certs with the parameters you decide.</p>
   *
   * <p>This is always seen as a clientside connection</p>
   *  
   * @param client the {@link TCPClient} to wrap to do ssl/tls on.
   * @param ssle The {@link SSLEngine} to use for the connection.
   */
  public SSLClient(TCPClient client, SSLEngine ssle) {
    this(client, ssle, true, true);
  }
  

  /**
   * <p>This constructor is for already existing connections. It also allows you to specify the {@link SSLEngine} 
   * to use to allow you to validate the servers certs with the parameters you decide.</p>
   *  
   * @param sei The {@link SocketExecuter} implementation this client will use.
   * @param channel The {@link SocketChannel} to use for this sslClient.
   * @param ssle The {@link SSLEngine} to use for the connection.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @param clientMode Set this to true if this is a client side connection, false if its server side.
   * @throws IOException This is thrown if there is a failure to use the provided {@link SocketChannel}. 
   */
  public SSLClient(SocketExecuter sei, SocketChannel channel, SSLEngine ssle, boolean doHandshake, boolean clientMode) throws IOException {
    this(new TCPClient(sei, channel), ssle, doHandshake, clientMode);
  }

  /**
   * <p>This constructor allows you to specify the SSLEngine to use to allow you to
   * validate the servers certs with the parameters you decide.</p>
   *  
   * @param client The {@link TCPClient} to wrap.
   * @param ssle The SSLEngine to use for the connection.
   * @param doHandshake This allows you to delay the handshake till a later negotiated time.
   * @param clientMode Set this to true if this is a client side connection, false if its server side.
   */
  public SSLClient(TCPClient client, SSLEngine ssle, boolean doHandshake, boolean clientMode){
    this.client = client;
    this.ssle = ssle;
    ssle.setUseClientMode(clientMode);
    encryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT);
    if(client.getReader() != null) {
      this.setReader(client.getReader());
      client.setReader(null);
    }

    if(client.getCloser() != null) {
      this.setCloser(client.getCloser());
      client.setCloser(null);
    }
    connectHandshake = doHandshake;
    if(connectHandshake) {
      doHandShake();
      client.connectionFuture.addCallback(new FutureCallback<Boolean>() {
        @Override
        public void handleResult(Boolean result) {
        }
        @Override
        public void handleFailure(Throwable t) {
          handshakeFuture.setFailure(t);
        }});
    }
    //These have to be set "after" we set handshake or encrypted data "might" slip through.
    client.setReader(tcpReader);
    client.setCloser(tcpCloser);
  }

  
  @Override
  public ListenableFuture<Boolean> connect(){
    return client.connect();
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
        write(ByteBuffer.allocate(0));
      }
      client.getClientsSocketExecuter().watchFuture(handshakeFuture, client.getTimeout());
    }
    return handshakeFuture;
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
    return client.getWriteBufferSize();
  }

  @Override
  public ListenableFuture<?> write(ByteBuffer buffer) {
    if(!isClosed() && !startedHandshake.get()){
      return client.write(buffer);
    } else if(!isClosed() && startedHandshake.get() && !finishedHandshake.get() && buffer.hasRemaining()) {
      throw new IllegalStateException("Can not write until handshake is finished!");
    }
    synchronized(ssle) {
      boolean gotFinished = false;
      ByteBuffer oldBB = buffer.duplicate();
      ByteBuffer newBB; 
      ByteBuffer tmpBB;
      List<ListenableFuture<?>> fl = new ArrayList<ListenableFuture<?>>();
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
            ListenableFuture<?> lf = client.write(tmpBB);
            fl.add(lf);
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
          write(ByteBuffer.allocate(0));
          handshakeFuture.setResult(ssle.getSession());
        }
      }
      return FutureUtils.makeCompleteFuture(fl);
    }
  }

  
  @Override
  public MergedByteBuffers getRead() {
    synchronized(decryptedReadList) {
      return decryptedReadList.duplicateAndClean();
    }
  }

  @Override
  public void setReader(final Reader reader) {
    if(!client.isClosed()) {
      this.sslReader = reader;
      synchronized(decryptedReadList) {
        if(this.decryptedReadList.remaining() > 0) {
          client.getClientsThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
              reader.onRead(SSLClient.this);
            }});
        }
      }
    }
  }
  
  @Override
  public void setCloser(Closer closer) {
    this.sslCloser = closer;
  }
  
  @Override
  public Closer getCloser() {
    return sslCloser;
  }

  private void doRead() {
    MergedByteBuffers clientBuffer = client.getRead();
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
              int size = decryptedReadList.remaining();
              Reader lreader = sslReader;
              decryptedReadList.add(newBB);
              if( size == 0 && lreader != null) {
                //Sync not needed here, this is only accessed by ReadThread.
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
      if(sslReader != null) {
        sslReader.onRead(this);
      }
    }
  }

  private void processHandshake(HandshakeStatus status) {
    switch(status) {
    case FINISHED: {
      if(this.finishedHandshake.compareAndSet(false, true)){
        handshakeFuture.setResult(ssle.getSession());
      }
    } break;
    case NEED_TASK: {
      while (ssle.getHandshakeStatus() == NEED_TASK) {
        runTasks();
      }
    } break;
    case NEED_WRAP: {
      write(ByteBuffer.allocate(0));
    } break;
    case NOT_HANDSHAKING:
    default: {

    } break;

    }
  }


  @Override
  protected boolean canRead() {
    return client.canRead();
  }

  @Override
  protected boolean canWrite() {
    return client.canWrite();
  }

  @Override
  public boolean hasConnectionTimedOut() {
    return client.hasConnectionTimedOut();
  }

  @Override
  protected void setConnectionStatus(Throwable t) {
    client.setConnectionStatus(t);
  }

  @Override
  public int getTimeout() {
    return client.getTimeout();
  }

  @Override
  protected ByteBuffer provideReadByteBuffer() {
    return client.provideReadByteBuffer();
  }

  @Override
  public int getReadBufferSize() {
    return client.getReadBufferSize() + this.decryptedReadList.remaining();
  }

  @Override
  public int getMaxBufferSize() {
    return client.getMaxBufferSize();
  }

  @Override
  public Executor getClientsThreadExecutor() {
    return client.getClientsThreadExecutor();
  }

  @Override
  public SocketExecuter getClientsSocketExecuter() {
    return client.getClientsSocketExecuter();
  }

  @Override
  public Reader getReader() {
    return sslReader;
  }

  @Override
  public void setMaxBufferSize(int size) {
    client.setMaxBufferSize(size);
  }

  @Override
  protected void addReadBuffer(ByteBuffer bb) {

  }

  @Override
  protected ByteBuffer getWriteBuffer() {
    return ByteBuffer.allocate(0);
  }

  @Override
  protected void reduceWrite(int size) {
  }

  @Override
  protected SocketChannel getChannel() {
    return client.getChannel();
  }

  @Override
  public WireProtocol getProtocol() {
    return WireProtocol.FAKE;
  }

  @Override
  protected Socket getSocket() {
    return client.getSocket();
  }

  @Override
  public boolean isClosed() {
    return client.isClosed();
  }

  @Override
  public void close() {
    client.close();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return client.getRemoteSocketAddress();
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return client.getLocalSocketAddress();
  }

  @Override
  public SimpleByteStats getStats() {
    return client.getStats();
  }
  
  public TCPClient getTCPClient() {
    return client;
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
  
  /**
   * Reader for SSLClient.  This is used to read from the TCPClient into the SSLClient. 
   */
  private class SSLCloser implements Closer {
    @Override
    public void onClose(Client client) {
      if(sslCloser != null) {
        sslCloser.onClose(SSLClient.this);
      }
    }
  }

  @Override
  public void setConnectionTimeout(int timeout) {
    client.setConnectionTimeout(timeout);
  }
    
}
