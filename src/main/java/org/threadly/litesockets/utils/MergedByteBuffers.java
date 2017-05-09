package org.threadly.litesockets.utils;

import java.nio.ByteBuffer;

import org.threadly.litesockets.buffers.ReuseableMergedByteBuffers;

/**
 * <p> MergedByteBuffers, this has been changed to an interface {@link org.threadly.litesockets.buffers.MergedByteBuffers} and the 
 * implementation {@link ReuseableMergedByteBuffers}.</p> 
 * 
 * @deprecated to {@link ReuseableMergedByteBuffers}
 * 
 * @author lwahlmeier
 *
 */
@Deprecated 
public class MergedByteBuffers extends ReuseableMergedByteBuffers  {

  public MergedByteBuffers() {
    this(true);
  }

  public MergedByteBuffers(boolean readOnly) {
    super(readOnly);
  }

  public MergedByteBuffers(boolean readOnly, ByteBuffer ...bbs) {
    super(readOnly, bbs);
  }
}
