package org.threadly.litesockets;

/**
 * This exception in thrown when we have problems doing common operations during startup.
 * This is usually around opening selectors.
 */
public class StartupException extends RuntimeException {

  private static final long serialVersionUID = 358704530394209047L;
  public StartupException(final Throwable t) {
    super(t);
  }

}
