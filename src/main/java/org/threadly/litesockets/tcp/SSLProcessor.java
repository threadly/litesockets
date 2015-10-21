package org.threadly.litesockets.tcp;

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
import javax.net.ssl.SSLSession;

import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.litesockets.utils.TransactionalByteBuffers;

/**
 * This is a generic SSLClient that can be used to create an encrypted connection to a server.
 * By default it will not check the servers certs and will only do TLS connections.
 * 
 * @author lwahlmeier
 */
class SSLProcessor {
  public static final int EXTRA_BUFFER_AMOUNT = 50;
  public static final int PREALLOCATE_BUFFER_MULTIPLIER = 3;

  private final AtomicBoolean finishedHandshake = new AtomicBoolean(false); 
  private final AtomicBoolean startedHandshake = new AtomicBoolean(false);
  private final SettableListenableFuture<SSLSession> handshakeFuture = new SettableListenableFuture<SSLSession>(false);
  private final TransactionalByteBuffers encryptedReadBuffers = new TransactionalByteBuffers();
  private final SSLEngine ssle;
  private final Client client;
  private ByteBuffer writeBuffer;
  private ByteBuffer decryptedReadBuffer;

  public SSLProcessor(Client client, SSLEngine ssle) {
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
      Runnable task = ssle.getDelegatedTask();
      task.run();
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

  public MergedByteBuffers write(ByteBuffer buffer) {
    MergedByteBuffers mbb = new MergedByteBuffers();
    if(!startedHandshake.get()){
      mbb.add(buffer);
      return mbb;
    }
    ByteBuffer oldBB = buffer.duplicate();
    ByteBuffer newBB; 
    ByteBuffer tmpBB;
    boolean gotFinished = false;
    while (ssle.getHandshakeStatus() == NEED_WRAP || oldBB.remaining() > 0) {
      newBB = getAppWriteBuffer();
      tmpBB = newBB.duplicate();
      try {
        SSLEngineResult res = ssle.wrap(oldBB, newBB);
        if(res.getHandshakeStatus() == FINISHED) {
          gotFinished = true;
        } else {
          while (ssle.getHandshakeStatus() == NEED_TASK) {
            runTasks();
          }
        }
      } catch (SSLException e) {
        throw new EncryptionException(e);
      }
      if(tmpBB.hasRemaining()) {
        tmpBB.limit(newBB.position());
        mbb.add(tmpBB);
      }
    }
    if(gotFinished) {
      if(finishedHandshake.compareAndSet(false, true)) {
        handshakeFuture.setResult(ssle.getSession());
        client.write(ByteBuffer.allocate(0));
      }
    }
    return mbb;
  }

  public MergedByteBuffers doRead(MergedByteBuffers bb) {
    MergedByteBuffers mbb = new MergedByteBuffers();
    if(!this.startedHandshake.get()) {
      mbb.add(bb);
      return mbb;
    }
    encryptedReadBuffers.add(bb);
    ByteBuffer encBB = encryptedReadBuffers.pull(encryptedReadBuffers.remaining());
//    ByteBuffer enc2bb = ByteBuffer.allocate(encBB.remaining());
//    enc2bb.put(encBB);
//    enc2bb.flip();
//    encBB = enc2bb;
    while(encBB.remaining() > 0) {
      ByteBuffer dbb = getDecryptedByteBuffer();
      ByteBuffer newBB = dbb.duplicate();
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

  private void processHandshake(HandshakeStatus status) throws SSLException {
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
    public EncryptionException(Throwable t) {
      super(t);
    }
  }
}
