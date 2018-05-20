package org.threadly.litesockets.utils;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.ClientSettableListenableFuture;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.litesockets.buffers.SimpleMergedByteBuffers;
import org.threadly.util.ExceptionUtils;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 */
public class SSLProcessor {
  //Not sure why but android needs this extra buffer
  //as the getApp/Packet buffers are not right.
  public static final int EXTRA_BUFFER_AMOUNT = 50;
  
  /**
   * This is how much extra buffer we allocate for read events so we do quite
   * as much allocating.  We always have to have at least 1 buffer the size SSLEngine says
   * we need, but might use very little of it.  This way we can allocate once and get multiple
   * read events with it before we have to throw away allocated bytes an allocate more. 
   * 
   */
  public static final int PREALLOCATE_BUFFER_MULTIPLIER = 3;

  private final AtomicBoolean finishedHandshake = new AtomicBoolean(false); 
  private final AtomicBoolean startedHandshake = new AtomicBoolean(false);
  private final SettableListenableFuture<SSLSession> handshakeFuture;
  private final MergedByteBuffers encryptedReadBuffers = new ReuseableMergedByteBuffers(false);
  private final MergedByteBuffers tempBuffers = new ReuseableMergedByteBuffers(false); 
  private final SSLEngine ssle;
  private final Client client;
  private ByteBuffer writeBuffer;
  private ByteBuffer decryptedReadBuffer;

  public SSLProcessor(final Client client, final SSLEngine ssle) {
    this.handshakeFuture = new ClientSettableListenableFuture<>(client);
    this.client = client;
    this.ssle = ssle;
  }
  
  public boolean handShakeStarted() {
    return startedHandshake.get();
  }

  /**
   * This lets you know if the connection is currently encrypted or not.
   * @return true if the connection is encrypted false if not.
   */
  public boolean isEncrypted() {
    return (startedHandshake.get() && !ssle.getSession().getProtocol().equals("NONE"));
  }

  /**
   * <p>You can start the handshake by calling this method.  If connect() has not been called
   * on the client this will happen as soon as it is.  The future allows you to know
   * when the handshake has finished if if there was an error.  While the handshake is processing all writes to the 
   * socket will queue.</p>
   * 
   * @return A ListenableFuture.  If a result was given it succeeded, if there is an error it failed.  The connection is closed on failures.
   */
  public ListenableFuture<SSLSession> doHandShake() {
    if(startedHandshake.compareAndSet(false, true)) {
      try {
        ssle.beginHandshake();
        if(ssle.getHandshakeStatus() == NEED_WRAP) {
          client.write(IOUtils.EMPTY_BYTEBUFFER);
        }
        client.getClientsSocketExecuter().watchFuture(handshakeFuture, client.getTimeout());
      } catch (SSLException e) {
        this.handshakeFuture.setFailure(e);
      }
    }
    return handshakeFuture;
  }

  private void runTasks() {
    SSLEngineResult.HandshakeStatus hs = ssle.getHandshakeStatus();
    while(hs == NEED_TASK) {
      final Runnable task = ssle.getDelegatedTask();
      if(task != null) {
        try {
          task.run();
        } catch (Throwable t) {
          ExceptionUtils.handleException(t);
        }
      }
      hs = ssle.getHandshakeStatus();
    }
  }

  private ByteBuffer getAppWriteBuffer() {
    if(this.writeBuffer == null || this.writeBuffer.remaining() < ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT) {
      this.writeBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT);
    }
    return writeBuffer;
  }
  
  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()+EXTRA_BUFFER_AMOUNT) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()+EXTRA_BUFFER_AMOUNT);
    }
    return decryptedReadBuffer;
  }

  public MergedByteBuffers encrypt(final ByteBuffer buffer) throws EncryptionException {
    return encrypt(new SimpleMergedByteBuffers(false, buffer));
  }
  
  public MergedByteBuffers encrypt(final MergedByteBuffers lmbb) throws EncryptionException {
    if(!startedHandshake.get()){
      return lmbb;
    }
    final MergedByteBuffers mbb = new ReuseableMergedByteBuffers(false);
    tempBuffers.add(lmbb);
    ByteBuffer oldBB = tempBuffers.pullBuffer(tempBuffers.remaining());
    ByteBuffer newBB; 
    ByteBuffer tmpBB;
    boolean gotFinished = false;
    while (ssle.getHandshakeStatus() == NEED_WRAP || oldBB.remaining() > 0) {
      newBB = getAppWriteBuffer();
      tmpBB = newBB.duplicate();
      try {
        final SSLEngineResult res = ssle.wrap(oldBB, newBB);
        if(!finishedHandshake.get() && oldBB.remaining() > 0) {
          tempBuffers.add(oldBB);
          oldBB.position(oldBB.limit());
        }
        if(!finishedHandshake.get() && res.getHandshakeStatus() == FINISHED) {
          gotFinished = true;
        } else {
          while (ssle.getHandshakeStatus() == NEED_TASK) {
            runTasks();
          }
        }
      } catch (SSLHandshakeException e) {
        this.handshakeFuture.setFailure(e);
        client.close();
        throw new EncryptionException(e);
      } catch (SSLException e) {
        throw new EncryptionException(e);
      }
      if(tmpBB.hasRemaining()) {
        tmpBB.limit(newBB.position());
        mbb.add(tmpBB);
      }
      if(client.isClosed()) {
        break;
      }
    }
    writeBuffer = null;
    if(gotFinished && finishedHandshake.compareAndSet(false, true)) {
      handshakeFuture.setResult(ssle.getSession());
      if(tempBuffers.remaining() > 0) {
        mbb.add(encrypt(IOUtils.EMPTY_BYTEBUFFER));
      }
    }
    return mbb;
  }
  
  public MergedByteBuffers decrypt(final ByteBuffer bb) throws EncryptionException {
    MergedByteBuffers mbb = new ReuseableMergedByteBuffers(false);
    mbb.add(bb);
    return decrypt(mbb);
  }
  
  public ReuseableMergedByteBuffers decrypt(final MergedByteBuffers bb) throws EncryptionException {
    final ReuseableMergedByteBuffers mbb = new ReuseableMergedByteBuffers(false);
    if(!this.startedHandshake.get()) {
      mbb.add(bb);
      return mbb;
    }
    encryptedReadBuffers.add(bb);
    final ByteBuffer encBB = encryptedReadBuffers.pullBuffer(encryptedReadBuffers.remaining());
    while(encBB.remaining() > 0) {
      int lastSize = encBB.remaining();
      final ByteBuffer dbb = getDecryptedByteBuffer();
      final ByteBuffer newBB = dbb.duplicate();
      SSLEngineResult res;
      try {
        res = ssle.unwrap(encBB, dbb);
        //We have to check both each time till complete
        if(! handshakeFuture.isDone()) {
          processHandshake(res.getHandshakeStatus());
          processHandshake(ssle.getHandshakeStatus());
        }
      } catch (SSLException e) {
        throw new EncryptionException(e);
      }
      newBB.limit(dbb.position());
      if(newBB.hasRemaining()) {
        mbb.add(newBB);
      } else if (res.getStatus() == Status.BUFFER_UNDERFLOW || (lastSize > 0 && lastSize == encBB.remaining())) {
        if(encBB.hasRemaining()) {
          encryptedReadBuffers.add(encBB);
        }
        break;
      }
    }
    return mbb;

  }
  
  public void failHandshake(Throwable t) {
    handshakeFuture.setFailure(t);
  }
  
  private void finishHandshake() {
    if(this.finishedHandshake.compareAndSet(false, true)){
      handshakeFuture.setResult(ssle.getSession());
      if(tempBuffers.remaining() > 0) {
        client.write(IOUtils.EMPTY_BYTEBUFFER); //make the client write to flush tempBuffers
      }
    }
  }

  private void processHandshake(final HandshakeStatus status) throws SSLException {
    switch(status) {
    case NOT_HANDSHAKING:
      //Fix for older android versions, they dont send a finished
    case FINISHED: {
      if(handShakeStarted()) {
        finishHandshake();
      }
    } break;
    case NEED_TASK: {
      runTasks();
    } break;
    case NEED_WRAP: {
      client.write(IOUtils.EMPTY_BYTEBUFFER);
    } break;

    default: {

    } break;

    }
  }
  
  /**
   * Generic Exception to throw when we get an Encryption error.
   * 
   * @author lwahlmeier
   *
   */
  public static class EncryptionException extends Exception {

    private static final long serialVersionUID = -2713992763314654069L;
    public EncryptionException(final Throwable t) {
      super(t);
    }
  }
}
