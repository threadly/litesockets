package org.threadly.litesockets.networkutils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import org.threadly.concurrent.AbstractService;
import org.threadly.concurrent.SimpleSchedulerInterface;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.Client.Closer;
import org.threadly.litesockets.Client.Reader;
import org.threadly.litesockets.Server.ClientAcceptor;
import org.threadly.litesockets.SocketExecuterInterface;
import org.threadly.litesockets.tcp.TCPServer;
import org.threadly.litesockets.utils.MergedByteBuffers;
import org.threadly.util.ExceptionUtils;
import org.threadly.util.debug.Profiler;


/**
 * The ProfileServer Uses Threadlys {@link Profiler} tying it to a listen socket to make it easy
 * to profile running java processes as needed. 
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
public class ProfileServer extends AbstractService implements ClientAcceptor, Reader, Closer{
  protected static final ByteBuffer DUMP_EXCEPTION = ByteBuffer.wrap("Got Exception doing Dump!\n\n".getBytes()).asReadOnlyBuffer();
  protected static final ByteBuffer BAD_DATA = ByteBuffer.wrap("Got Bad Data from you, closing!!\n\n".getBytes()).asReadOnlyBuffer();
  protected static final ByteBuffer STARTED_RESPONSE = ByteBuffer.wrap("Profiler Started\n\n".getBytes()).asReadOnlyBuffer();
  protected static final ByteBuffer ALREADY_STARTED_RESPONSE = ByteBuffer.wrap("Profiler Already Started\n\n".getBytes()).asReadOnlyBuffer();
  protected static final ByteBuffer STOPPED_RESPONSE = ByteBuffer.wrap("Profiler Stopped\n\n".getBytes()).asReadOnlyBuffer();
  protected static final ByteBuffer ALREADY_STOPPED_RESPONSE = ByteBuffer.wrap("Profiler is not Running\n\n".getBytes()).asReadOnlyBuffer();
  protected static final ByteBuffer RESET_RESPONSE = ByteBuffer.wrap("Profiler Reset\n\n".getBytes()).asReadOnlyBuffer();
  protected static final String START_DUMP = "---------------------START-DUMP---------------------------------\n";
  protected static final String END_DUMP = "\n---------------------END-DUMP-----------------------------------\n\n";
  protected static final String START_PROFILE = "start";
  protected static final String STOP_PROFILE = "stop";
  protected static final String RESET_PROFILE = "reset";
  protected static final String DUMP_PROFILE = "dump";
  protected static final ByteBuffer HELP;
  static {
    StringBuilder sb = new StringBuilder();
    sb.append("HELP MENU:\n");
    sb.append(START_PROFILE).append(" - Starts the profiler\n");
    sb.append(STOP_PROFILE).append(" - Stops the profiler (Profile is maintained)\n");
    sb.append(RESET_PROFILE).append(" - Resets the profilers data\n");
    sb.append(DUMP_PROFILE).append(" - dumps the profiler current data\n");
    HELP = ByteBuffer.wrap(sb.toString().getBytes()).asReadOnlyBuffer();
  }

  private final SimpleSchedulerInterface scheduler;
  private final SocketExecuterInterface socketEx;
  private final ConcurrentHashMap<Client, MergedByteBuffers> clients = new ConcurrentHashMap<Client, MergedByteBuffers>();
  private final Profiler profiler;
  private final String host;
  private final int port;
  private TCPServer server;
  
  
  /**
   * Construct a ProileServer
   * 
   * @param socketEx The SocketExecuterInterface to use with this Server.
   * @param host the host to create the servers listen port on.
   * @param port the port to use.
   * @param frequency the frequency of the profiler in ms. 
   */
  public ProfileServer(SocketExecuterInterface socketEx, String host, int port, int frequency) {
    scheduler = socketEx.getThreadScheduler();
    this.socketEx = socketEx;
    profiler = new Profiler(frequency);
    this.host = host;
    this.port = port;
  }
  
  @Override
  public void onClose(Client client) {
    clients.remove(client);
  }

  @Override
  public void onRead(final Client client) {
    MergedByteBuffers mbb = clients.get(client);
    mbb.add(client.getRead());
    int pos = -1;
    while((pos = mbb.indexOf("\n")) > -1) {
      String cmd = mbb.getAsString(pos).trim().toLowerCase();
      mbb.discard(1);
      if(START_PROFILE.equals(cmd)) {
        if(!profiler.isRunning()) {
          profiler.start();
          client.writeForce(STARTED_RESPONSE.duplicate());
        } else {
          client.writeForce(ALREADY_STARTED_RESPONSE.duplicate());
        }
      } else if(STOP_PROFILE.equals(cmd)) {
        if(profiler.isRunning()) {
          profiler.stop();
          client.writeForce(STOPPED_RESPONSE.duplicate());
        } else {
          client.writeForce(ALREADY_STOPPED_RESPONSE.duplicate());
        }
      } else if(RESET_PROFILE.equals(cmd)) {
        profiler.reset();
        client.writeForce(RESET_RESPONSE.duplicate());
      } else if(DUMP_PROFILE.equals(cmd)) {
        dumpProfile(client);
      } else {
        sendHelp(client);
      }
    }
    if(mbb.remaining() > 100) {
      client.setReader(null);
      client.writeForce(BAD_DATA.duplicate());
      this.scheduler.schedule(new Runnable() {
        @Override
        public void run() {
          client.close();
        }}, 500);
    }
        
  }

  @Override
  public void accept(Client client){
    try {
      clients.put(client, new MergedByteBuffers());
      client.setReader(this);
      client.setCloser(this);
      socketEx.addClient(client);
    } catch (Exception e) {
      ExceptionUtils.handleException(e);
    }
  }
  
  private void sendHelp(final Client client) {
    scheduler.execute(new Runnable() {
      @Override
      public void run() {
        client.writeForce(HELP.duplicate());
      }
    });
  }
  
  private void dumpProfile(final Client client) {
    scheduler.execute(new Runnable() {

      @Override
      public void run() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(0);
        OutputStream os = new BufferedOutputStream(baos);
        try {
          os.write(START_DUMP.getBytes());
          profiler.dump(os);
          os.write(END_DUMP.getBytes());
          os.write("\n".getBytes());
        } catch(IOException e) {
          client.writeForce(DUMP_EXCEPTION.duplicate());
          client.writeForce(ByteBuffer.wrap(ExceptionUtils.stackToString(e).getBytes()));
        } finally {
          try {
            os.close();
          } catch (IOException e) {
          }
        }
        ByteBuffer dump = ByteBuffer.wrap(baos.toByteArray());
        client.writeForce(dump);
      }
      
    });
  }

  @Override
  protected void startupService() {
    try {
      server = new TCPServer(host, port);
      server.setClientAcceptor(this);
      socketEx.startIfNotStarted();
      socketEx.addServer(server);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  protected void shutdownService() {
    socketEx.removeServer(server);
    profiler.stop();
    profiler.reset();
    server.close();
  }
}
