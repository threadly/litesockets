package org.threadly.litesockets.networkutils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.SubmitterScheduler;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.CloseListener;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.TCPServer;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.util.AbstractService;
import org.threadly.util.ExceptionUtils;
import org.threadly.util.debug.Profiler;


/**
 * <p>The ProfileServer Uses Threadly's {@link Profiler} tying it to a listen socket to make it easy
 * to profile running java processes as needed.</p> 
 *
 * This will open a listen port on specified ip/port and allow connections to send basic commands to the
 * process to control the profiler.
 * 
 * Here are a list of commands:
 * 
 * start - Starts the profiler
 * stop - Stops the profiler (Profile is maintained)
 * reset - Resets the profilers data
 * dump - dumps the profiler current data
 * 
 * Commands must end with a newline.
 * 
 * NOTE: the profiler should only be used when needed and stopped and reset when not in use.  The longer it
 * runs the more CPU and Memory it will consume, to the point where it could eat up an entire CPU core and over fill
 * memory.
 *
 */
public class ProfileServer extends AbstractService implements ClientAcceptor, Reader, CloseListener{
  public static final int DISCONNECT_DELAY = 500;
  private static final Charset DEFAULT_CHARSET = Charset.forName("US-ASCII");
  protected static final ByteBuffer DUMP_EXCEPTION = ByteBuffer.wrap("Got Exception doing Dump!\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final ByteBuffer BAD_DATA = ByteBuffer.wrap("Got Bad Data from you, closing!!\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final ByteBuffer STARTED_RESPONSE = ByteBuffer.wrap("Profiler Started\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final ByteBuffer ALREADY_STARTED_RESPONSE = ByteBuffer.wrap("Profiler Already Started\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final ByteBuffer STOPPED_RESPONSE = ByteBuffer.wrap("Profiler Stopped\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final ByteBuffer ALREADY_STOPPED_RESPONSE = ByteBuffer.wrap("Profiler is not Running\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final ByteBuffer RESET_RESPONSE = ByteBuffer.wrap("Profiler Reset\n\n".getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  protected static final String START_DUMP = "---------------------START-DUMP---------------------------------\n";
  protected static final String END_DUMP = "\n---------------------END-DUMP-----------------------------------\n\n";
  protected static final String START_PROFILE = "start";
  protected static final String STOP_PROFILE = "stop";
  protected static final String RESET_PROFILE = "reset";
  protected static final String DUMP_PROFILE = "dump";
  protected static final ByteBuffer HELP;
  static {
    final StringBuilder sb = new StringBuilder(150);
    sb.append("HELP MENU:\n");
    sb.append(START_PROFILE).append(" - Starts the profiler\n");
    sb.append(STOP_PROFILE).append(" - Stops the profiler (Profile is maintained)\n");
    sb.append(RESET_PROFILE).append(" - Resets the profilers data\n");
    sb.append(DUMP_PROFILE).append(" - dumps the profiler current data\n");
    HELP = ByteBuffer.wrap(sb.toString().getBytes(DEFAULT_CHARSET)).asReadOnlyBuffer();
  }

  private final SubmitterScheduler scheduler;
  private final SocketExecuter socketEx;
  private final ConcurrentHashMap<Client, MergedByteBuffers> clients = new ConcurrentHashMap<Client, MergedByteBuffers>();
  private final Profiler profiler;
  private final String host;
  private final int port;
  private TCPServer server;
  
  
  /**
   * Constructs a ProileServer.
   * 
   * @param socketEx The SocketExecuterInterface to use with this Server.
   * @param host the host to create the servers listen port on.
   * @param port the port to use.
   * @param frequency the frequency of the profiler in ms. 
   */
  public ProfileServer(final SocketExecuter socketEx, final String host, final int port, final int frequency) {
    socketEx.startIfNotStarted();
    scheduler = socketEx.getThreadScheduler();
    this.socketEx = socketEx;
    profiler = new Profiler(frequency);
    this.host = host;
    this.port = port;
  }
  
  @Override
  public void onClose(final Client client) {
    clients.remove(client);
  }

  @Override
  public void onRead(final Client client) {
    final MergedByteBuffers mbb = clients.get(client);
    mbb.add(client.getRead());
    int pos = -1;
    while((pos = mbb.indexOf("\n")) > -1) {
      final String cmd = mbb.getAsString(pos).trim().toLowerCase();
      mbb.discard(1);
      if(START_PROFILE.equals(cmd)) {
        if(profiler.isRunning()) {
          client.write(ALREADY_STARTED_RESPONSE.duplicate());
        } else {
          profiler.start();
          client.write(STARTED_RESPONSE.duplicate());
        }
      } else if(STOP_PROFILE.equals(cmd)) {
        if(profiler.isRunning()) {
          profiler.stop();
          client.write(STOPPED_RESPONSE.duplicate());
        } else {
          client.write(ALREADY_STOPPED_RESPONSE.duplicate());
        }
      } else if(RESET_PROFILE.equals(cmd)) {
        profiler.reset();
        client.write(RESET_RESPONSE.duplicate());
      } else if(DUMP_PROFILE.equals(cmd)) {
        dumpProfile(client);
      } else {
        sendHelp(client);
      }
    }
    if(mbb.remaining() > 100) {
      client.setReader(null);
      client.write(BAD_DATA.duplicate()).addListener(new Runnable() {
        @Override
        public void run() {
          client.close();
        }});
    }
        
  }

  @Override
  public void accept(final Client client){
    try {
      clients.put(client, new MergedByteBuffers());
      client.setReader(this);
      client.addCloseListener(this);
      //socketEx.addClient(client);
    } catch (Exception e) {
      ExceptionUtils.handleException(e);
    }
  }
  
  private void sendHelp(final Client client) {
    scheduler.execute(new Runnable() {
      @Override
      public void run() {
        client.write(HELP.duplicate());
      }
    });
  }
  
  private void dumpProfile(final Client client) {
    scheduler.execute(new Runnable() {

      @Override
      public void run() {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(0);
        final OutputStream os = new BufferedOutputStream(baos);
        try {
          os.write(START_DUMP.getBytes(DEFAULT_CHARSET));
          profiler.dump(os);
          os.write(END_DUMP.getBytes(DEFAULT_CHARSET));
          os.write("\n".getBytes(DEFAULT_CHARSET));
        } catch(IOException e) {
          client.write(DUMP_EXCEPTION.duplicate());
          client.write(ByteBuffer.wrap(ExceptionUtils.stackToString(e).getBytes(DEFAULT_CHARSET)));
        } finally {
          try {
            os.close();
          } catch (IOException e) {
          }
        }
        client.write(ByteBuffer.wrap(baos.toByteArray()));
      }
      
    });
  }

  @Override
  protected void startupService() {
    try {
      server = socketEx.createTCPServer(host, port);
      server.setClientAcceptor(this);
      server.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  protected void shutdownService() {
    profiler.stop();
    profiler.reset();
    server.close();
  }
}
