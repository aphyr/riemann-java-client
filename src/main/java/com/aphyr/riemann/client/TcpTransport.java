package com.aphyr.riemann.client;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.net.*;
import java.io.*;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.group.*;
import org.jboss.netty.handler.codec.oneone.*;
import org.jboss.netty.handler.codec.protobuf.*;
import org.jboss.netty.handler.codec.frame.*;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import com.aphyr.riemann.Proto.Msg;
import org.jboss.netty.channel.socket.nio.*;
import java.util.concurrent.atomic.*;

public class TcpTransport implements AsynchronousTransport {
  // For writes we don't care about
  public static final Promise<Msg> blackhole = 
    new Promise<Msg>();

  // Shared pipeline handlers
  public static final ProtobufDecoder pbDecoder = 
    new ProtobufDecoder(Msg.getDefaultInstance());
  public static final ProtobufEncoder pbEncoder = 
    new ProtobufEncoder();
  public static final LengthFieldPrepender frameEncoder = 
    new LengthFieldPrepender(4);

  public static final int DEFAULT_PORT = 5555;

  // I AM A STATE MUSHEEN
  public enum State {
    DISCONNECTED,
     CONNECTING,
      CONNECTED,
      DISCONNECTING
  }

  // STATE STATE STATE
  public volatile State state = State.DISCONNECTED;
  public final ChannelGroup channels = new DefaultChannelGroup();
  public volatile Timer timer; 
  public volatile ClientBootstrap bootstrap;

  // Configuration
  public final AtomicLong reconnectDelay = new AtomicLong(5000);
  public final AtomicLong connectTimeout = new AtomicLong(5000);
  public final InetSocketAddress address;

  public TcpTransport(final InetSocketAddress address) {
    this.address = address;
  }

  public TcpTransport(final String host, final int port) throws IOException {
    this(new InetSocketAddress(host, port));
  }

  public TcpTransport(final String host) throws IOException {
    this(host, DEFAULT_PORT);
  }

  public TcpTransport(final int port) throws IOException {
    this(InetAddress.getLocalHost().getHostAddress(), port);
  }

  @Override
  public boolean isConnected() {
    // Are we in state connected?
    if (state != State.CONNECTED) {
      return false;
    }

    // Is at least one channel connected?
    for (Channel ch : channels) {
      if (ch.isConnected()) {
        return true;
      }
    }
    
    return false;
  }

  @Override
  // Does nothing if not currently disconnected.
  public synchronized void connect() throws IOException {
    if (state != State.DISCONNECTED) {
      return;
    }

    state = State.CONNECTING;

    // Set up channel factory
    final ChannelFactory channelFactory = new NioClientSocketChannelFactory(
        Executors.newCachedThreadPool(),
        Executors.newCachedThreadPool());

    // Timer
    timer = new HashedWheelTimer();

    // Create bootstrap
    bootstrap = new ClientBootstrap(channelFactory);

    // Set up pipeline factory.
    bootstrap.setPipelineFactory(
        new ChannelPipelineFactory() {
          public ChannelPipeline getPipeline() {
            final ChannelPipeline p = Channels.pipeline();

            p.addLast("reconnect", new ReconnectHandler(
                bootstrap,
                timer,
                reconnectDelay.get(),
                TimeUnit.MILLISECONDS));
            p.addLast("frame-decoder", new LengthFieldBasedFrameDecoder(
                Integer.MAX_VALUE, 0, 4, 0, 4));
            p.addLast("frame-encoder", frameEncoder);
            p.addLast("protobuf-decoder", pbDecoder);
            p.addLast("protobuf-encoder", pbEncoder);
            p.addLast("handler", new TcpHandler(channels));
            
            return p;
          }});

    // Set bootstrap options
    bootstrap.setOption("tcpNoDelay", true);
    bootstrap.setOption("keepAlive", true);
    bootstrap.setOption("remoteAddress", address);

    // Connect and wait for attempt
    final ChannelFuture result = bootstrap.connect().awaitUninterruptibly();

    // At this point we are "connected"--even though the connection may have
    // failed. The channel will continue to initiate reconnect attempts in the
    // background.
    state = State.CONNECTED;

    // We'll throw an exception so users can pretend this call is synchronous
    // (and log errors as appropriate) but the client might succeed later.
    if (! result.isSuccess()) {
      throw new IOException("Connection failed", result.getCause());
    }
  }

  @Override
  public void disconnect() throws IOException {
    disconnect(false);
  }

  public synchronized void disconnect(boolean force) throws IOException {
    if (!(force || state == State.CONNECTED)) {
      return;
    }

    try {
      timer.stop();
      channels.close().awaitUninterruptibly();
      bootstrap.releaseExternalResources();
    } finally {
      timer = null;
      bootstrap = null;
      state = State.DISCONNECTED;
    }
  }

  @Override
  public void reconnect() throws IOException {
    disconnect();
    connect();
  }

  // An Noop
  @Override
  public void flush() throws IOException {
  }

  // Write a message to any available handler and return promise.
  public Promise<Msg> write(final Msg msg, 
      final Promise<Msg> promise) {
    if (state == State.CONNECTED) {
      final Write write = new Write(msg, promise);
      // Write to any channel
      for (Channel channel : channels) {
        channel.write(new Write(msg, promise));
        return promise;
      }
      promise.deliver(new IOException("No channels available."));
    } else {
      promise.deliver(new IOException("Not connected."));
    }
    return promise;
  }

  @Override
  public void aSendMessage(final Msg msg) {
    write(msg, blackhole);
  }

  @Override
  public Promise<Msg> aSendRecvMessage(final Msg msg) {
    return write(msg, new Promise<Msg>());
  }

  @Override
  public Promise<Msg> aSendMaybeRecvMessage(final Msg msg) {
    return aSendRecvMessage(msg);
  }
}
