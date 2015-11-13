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
  private final SettableListenableFuture<SSLSession> handshakeFuture = new SettableListenableFuture<SSLSession>(false);
  private final TransactionalByteBuffers encryptedReadBuffers = new TransactionalByteBuffers(false);
  private final MergedByteBuffers tempBuffers = new MergedByteBuffers(false); 
  private final SSLEngine ssle;
  private final Client client;
  private ByteBuffer writeBuffer;
  private ByteBuffer decryptedReadBuffer;

  public SSLProcessor(final Client client, final SSLEngine ssle) {
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
          client.write(ByteBuffer.allocate(0));
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
      ExceptionUtils.runRunnable(task);
      hs = ssle.getHandshakeStatus();
    }
  }

  private ByteBuffer getAppWriteBuffer() {
    if(this.writeBuffer == null || this.writeBuffer.remaining() < ssle.getSession().getPacketBufferSize()+EXTRA_BUFFER_AMOUNT) {
      this.writeBuffer = ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()*PREALLOCATE_BUFFER_MULTIPLIER);
    }
    return ByteBuffer.allocate(ssle.getSession().getPacketBufferSize()*PREALLOCATE_BUFFER_MULTIPLIER);
  }
  
  private ByteBuffer getDecryptedByteBuffer() {
    if(decryptedReadBuffer == null || decryptedReadBuffer.remaining() < ssle.getSession().getApplicationBufferSize()+EXTRA_BUFFER_AMOUNT) {
      decryptedReadBuffer = ByteBuffer.allocate(ssle.getSession().getApplicationBufferSize()*PREALLOCATE_BUFFER_MULTIPLIER);
    }
    return decryptedReadBuffer;
  }

  public MergedByteBuffers encrypt(final ByteBuffer buffer) {
    final MergedByteBuffers mbb = new MergedByteBuffers(false);
    if(!startedHandshake.get()){
      mbb.add(buffer);
      return mbb;
    }
    ByteBuffer oldBB = buffer.duplicate();
    if(finishedHandshake.get() && this.tempBuffers.remaining() > 0) {
      tempBuffers.add(buffer);
      oldBB = tempBuffers.pull(tempBuffers.remaining());
    }
    
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
        break;
      } catch (SSLException e) {
        throw new EncryptionException(e);
      }
      if(tmpBB.hasRemaining()) {
        tmpBB.limit(newBB.position());
        mbb.add(tmpBB);
      }
    }
    if(gotFinished && finishedHandshake.compareAndSet(false, true)) {
      handshakeFuture.setResult(ssle.getSession());
      if(tempBuffers.remaining() > 0) {
        mbb.add(encrypt(ByteBuffer.allocate(0)));
      }
    }
    return mbb;
  }
  
  public MergedByteBuffers decrypt(final ByteBuffer bb) {
    MergedByteBuffers mbb = new MergedByteBuffers(false);
    mbb.add(bb);
    return decrypt(mbb);
  }
  
  public MergedByteBuffers decrypt(final MergedByteBuffers bb) {
    final MergedByteBuffers mbb = new MergedByteBuffers(false);
    if(!this.startedHandshake.get()) {
      mbb.add(bb);
      return mbb;
    }
    encryptedReadBuffers.add(bb);
    final ByteBuffer encBB = encryptedReadBuffers.pull(encryptedReadBuffers.remaining());
//    ByteBuffer enc2bb = ByteBuffer.allocate(encBB.remaining());
//    enc2bb.put(encBB);
//    enc2bb.flip();
//    encBB = enc2bb;
    while(encBB.remaining() > 0) {
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
      } else if (res.getStatus() == Status.BUFFER_UNDERFLOW) {
        if(encBB.hasRemaining()) {
          encryptedReadBuffers.add(encBB);
        }
        break;
      }
    }
    return mbb;

  }

  private void processHandshake(final HandshakeStatus status) throws SSLException {
    switch(status) {
    case FINISHED: {
      if(this.finishedHandshake.compareAndSet(false, true)){
        handshakeFuture.setResult(ssle.getSession());
        if(tempBuffers.remaining() > 0) {
          client.write(ByteBuffer.allocate(0)); //make the client write to flush tempBuffers
        }
      }
    } break;
    case NEED_TASK: {
      while (ssle.getHandshakeStatus() == NEED_TASK) {
        runTasks();
      }
    } break;
    case NEED_WRAP: {
      client.write(ByteBuffer.allocate(0));
    } break;
    case NOT_HANDSHAKING:
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
  public static class EncryptionException extends RuntimeException {

    private static final long serialVersionUID = -2713992763314654069L;
    public EncryptionException(final Throwable t) {
      super(t);
    }
  }
}
