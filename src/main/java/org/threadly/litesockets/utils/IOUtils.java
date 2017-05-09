package org.threadly.litesockets.utils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;
import org.threadly.util.ExceptionUtils;


/**
 * SimpleUtil functions for IO operations.
 *
 */
public class IOUtils {
  
  private IOUtils(){}

  /**
   * Default max buffer size (64k).  Read and write buffers are independent of each other.
   */
  public static final int DEFAULT_CLIENT_MAX_BUFFER_SIZE = 65536;
  
  /**
   * When we hit the minimum read buffer size we will create a new one of this size (64k).
   */
  public static final int DEFAULT_CLIENT_READ_BUFFER_SIZE = 65536;
  
  /**
   * Minimum allowed readBuffer (4k).  If the readBuffer is lower then this we will create a new one.
   */
  public static final int DEFAULT_MIN_CLIENT_READ_BUFFER_SIZE = 4096;
  
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  
  /**
   * Simple empty ByteBuffer to use when passing a ByteBuffer of 0 length.
   */
  public static final ByteBuffer EMPTY_BYTEBUFFER = ByteBuffer.wrap(EMPTY_BYTE_ARRAY);

  public static final ListenableFuture<Long> FINISHED_LONG_FUTURE = FutureUtils.immediateResultFuture(0L);

  
  /**
   * Another implementation of a silent closing function.
   * 
   * @param closer
   */
  public static void closeQuietly(Closeable closer) {
    try {
      if(closer != null) {
        closer.close();
      }
    } catch(Throwable t) {
      
    }
  }
  
  private static void blockWriteFuture(ListenableFuture<?> writeFuture) throws IOException {
    try {
      writeFuture.get(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException)cause;
      } else {
        throw new IOException(cause);
      }
    } catch (CancellationException | TimeoutException e) {
      throw new IOException(e);
    }
  }
  
  
  /**
   * This creates an {@link OutputStream} from a client.
   * Like all {@link OutputStream} streams calling write will block until
   * is it able to write the data.
   * 
   */
  public static class ClientOutputStream extends OutputStream {
    
    private final Client c;
    private volatile boolean isClosed = false;
    private volatile ListenableFuture<?> lastWriteFuture;
    
    public ClientOutputStream(Client c) {
      this.c = c;
      c.addCloseListener((client)->{
          isClosed = true;
      });
      lastWriteFuture = c.lastWriteFuture();
    }

    @Override
    public void write(byte[] ba, int off, int len) throws IOException {
      while(true) {
        if(lastWriteFuture.isDone() && !isClosed) {
          ByteBuffer bb = ByteBuffer.wrap(ba, off, len);
          lastWriteFuture = c.write(bb);
          return;
        } else {
          blockWriteFuture(lastWriteFuture);
        }
      }
    }
    
    @Override
    public void write(int arg0) throws IOException {
      while(true) {
        if(lastWriteFuture.isDone() && !isClosed) {
          lastWriteFuture = c.write(ByteBuffer.wrap(new byte[]{(byte) arg0}));
          return;
        } else {
          blockWriteFuture(lastWriteFuture);
        }
      }
    }
  }
  
  
  /**
   * This creates an {@link InputStream} from a client.
   * Like all other {@link InputStream} it will block on read until at least
   * some data is available.
   * 
   *
   */
  public static class ClientInputStream extends InputStream {
    private final Client c;
    private final MergedByteBuffers currentBB = new ReuseableMergedByteBuffers();
    private volatile boolean isClosed = false;
    
    public ClientInputStream(Client c) {
      this.c=c;
      c.addCloseListener((client)->{
        isClosed = true;
        synchronized(currentBB) {
          currentBB.notifyAll();
        }
      });
      c.setReader((client)->{
        synchronized(currentBB) {
          if(currentBB.remaining() == 0) {
            currentBB.add(c.getRead());
          }
          currentBB.notifyAll();
        }
      });
    }

    @Override
    public int read(byte[] ba, int offset, int len) throws IOException {
      if(isClosed) {
        return -1;
      }
      synchronized(currentBB) {
        while(true) {
          if(c.getReadBufferSize() > 0) {
            currentBB.add(c.getRead());
          }
          if(currentBB.remaining() >= len) {
            currentBB.get(ba, offset, len);
            return len;
          } else {
            if(isClosed) {
              return -1;
            } else {
              try {
                currentBB.wait(1000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ExceptionUtils.handleException(e);
              }
            }
          }
        }
      }
    }
    
    @Override
    public int read() throws IOException {
      if(isClosed) {
        return -1;
      }
      synchronized(currentBB) {
        while(true) {
          if(currentBB.remaining() > 0) {
            return currentBB.get() & MergedByteBuffers.UNSIGNED_BYTE_MASK;
          } else {
            if(c.getReadBufferSize() > 0) {
              currentBB.add(c.getRead());
            } else if(isClosed) {
              return -1;
            } else {
              try {
                currentBB.wait(1000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ExceptionUtils.handleException(e);
              }
            }
          }
        }
      }
    }
  }
}
