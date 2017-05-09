package org.threadly.litesockets.utils;

/**
 * 
 * This allows you to used MergedByteBuffers in a transactional way.  This can be useful when reading
 * from the socket using a protocol that is not framed.
 * 
 * NOTE:  If you are modifying the data in the underlying byteArrays that will not be reverted
 * 
 * @deprecated this has been replaced by {@link org.threadly.litesockets.buffers.TransactionalByteBuffers}
 * 
 * @author lwahlmeier
 *
 */
@Deprecated 
public class TransactionalByteBuffers extends org.threadly.litesockets.buffers.TransactionalByteBuffers  {
  public TransactionalByteBuffers() {
    super();
  }
  
  public TransactionalByteBuffers(boolean readOnly) {
    super(readOnly);
  }
}
