package org.threadly.litesockets.utils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.CloseListener;
import org.threadly.litesockets.Client.Reader;

public class IOUtils {
  

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

  
  public static void closeQuitly(Closeable closer) {
    try {
      if(closer != null) {
        closer.close();
      }
    } catch(Throwable t) {
      
    }
  }
  
  public static InputStream makeClientInputStream(Client c) {
    return null;
  }
  
  public static class ClientOutputStream extends OutputStream {
    
    private final Client c;
    private volatile boolean isClosed = false;
    private volatile ListenableFuture<?> lastWriteFuture;
    
    public ClientOutputStream(Client c) {
      this.c = c;
      c.addCloseListener(new CloseListener() {
        @Override
        public void onClose(Client client) {
          isClosed = true;
        }
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
          try {
            lastWriteFuture.get(1000, TimeUnit.MILLISECONDS);
          } catch (Exception e) {
            e.printStackTrace();
          }
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
          try {
            lastWriteFuture.get(1000, TimeUnit.MILLISECONDS);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    }
    
  }
  
  public static class ClientInputStream extends InputStream {
    private final Client c;
    private final MergedByteBuffers currentBB = new MergedByteBuffers();
    private volatile boolean isClosed = false;
    
    public ClientInputStream(Client c) {
      this.c=c;
      c.addCloseListener(new CloseListener() {
        @Override
        public void onClose(Client client) {
          isClosed = true;
          synchronized(currentBB) {
            currentBB.notifyAll();
          }
        }
      });
      c.setReader(new Reader() {
        @Override
        public void onRead(Client client) {
          synchronized(currentBB) {
            while(c.getReadBufferSize() > 0) {
              if(currentBB.remaining() == 0) {
                currentBB.add(c.getRead());
                currentBB.notifyAll();
              } else {
                try {
                  currentBB.wait();
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              }
            }
          }
        }});
    }

    @Override
    public int read(byte[] ba, int offset, int len) throws IOException {
      while(true) {
        synchronized(currentBB) {
          if(currentBB.remaining() >= len) {
            ByteBuffer bb = currentBB.pull(len);
            bb.get(ba, offset, len);
            if(currentBB.remaining() == 0) {
              currentBB.notifyAll();
            }
            return len;
          } else {
            if(isClosed) {
              return -1;
            } else {
              try {
                currentBB.wait(1000);
              } catch (Exception e) {

              }
            }
          }
        }
      }
    }
    
    @Override
    public int read() throws IOException {
      while(true) {
        synchronized(this) {
          if(currentBB.remaining() > 0) {
            return currentBB.get()&0xff;
          } else {
            if(c.getReadBufferSize() > 0) {
              currentBB.add(c.getRead());
            } else if(isClosed) {
              return -1;
            } else {
              try {
                this.wait(1000);
              } catch (InterruptedException e) {

              }
            }
          }
        }
      }
    }
  }
}
