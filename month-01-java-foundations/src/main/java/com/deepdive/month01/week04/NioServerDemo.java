package com.deepdive.month01.week04;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Week 4: Non-Blocking I/O (NIO) - Selector-Based Server
 *
 * CONCEPT: Traditional Java I/O (java.io) is blocking:
 * - Each connection requires a dedicated thread
 * - Thread blocks on read/write waiting for data
 * - C10K problem: 10,000 connections = 10,000 threads = OOM
 *
 * NIO (java.nio) solution:
 * - Selector: multiplexes many channels on a single thread
 * - Channels: non-blocking I/O (read/write returns immediately)
 * - ByteBuffers: direct memory access without copying
 *
 * This is the basis of:
 * - Netty, Undertow, Jetty NIO connector
 * - Spring WebFlux (via Reactor Netty)
 * - All high-performance Java servers
 *
 * Key classes:
 * - Selector:              Monitors multiple channels for readiness
 * - SelectionKey:          Channel + interest ops + attachment
 * - ServerSocketChannel:   Non-blocking TCP server socket
 * - SocketChannel:         Non-blocking TCP client socket
 * - ByteBuffer:            Buffer for channel I/O
 *
 * Interest operations:
 * - OP_ACCEPT:  Server ready to accept new connections
 * - OP_CONNECT: Client connected (client-side)
 * - OP_READ:    Channel has data to read
 * - OP_WRITE:   Channel ready to accept writes
 *
 * NOTE: Java 21 Virtual Threads significantly reduce the need for NIO-based servers
 * because you can use blocking I/O per virtual thread without OS thread overhead.
 * But understanding NIO is still essential for system programming and library work.
 */
public class NioServerDemo {

    private static final int PORT = 9090;
    private static final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("=== NIO Non-Blocking Server Demo ===");
        System.out.println("Architecture: Single-thread selector loop handles all connections");

        // Start server in background
        Thread serverThread = new Thread(NioServerDemo::runServer, "nio-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Give server time to start
        Thread.sleep(200);

        // Run test clients
        demonstrateClients();

        // Shutdown
        running.set(false);
        serverThread.join(2000);
        System.out.println("Server stopped.");
        explainNioComponents();
    }

    /**
     * CONCEPT: The Selector event loop - the heart of non-blocking servers.
     * One thread handles thousands of connections via I/O readiness notification.
     *
     * Event loop pattern (also used by Node.js, Netty, Redis):
     * 1. Register channels with selector
     * 2. select() blocks until at least one channel is ready
     * 3. Process all ready channels
     * 4. Repeat
     */
    private static void runServer() {
        try (Selector selector = Selector.open();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {

            // CONCEPT: Non-blocking mode - operations return immediately
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(PORT));

            // Register server channel with selector for ACCEPT events
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("NIO Server listening on port " + PORT);

            while (running.get()) {
                // CONCEPT: select() blocks until at least one channel is ready
                // select(timeout) prevents blocking forever
                int readyChannels = selector.select(500);

                if (readyChannels == 0) continue; // Timeout, check running flag

                // CONCEPT: selectedKeys() returns only READY channels
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove(); // CRITICAL: Must remove to prevent reprocessing

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key, selector);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException e) {
                        System.err.println("Channel error: " + e.getMessage());
                        closeKey(key);
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) System.err.println("Server error: " + e.getMessage());
        }
    }

    /**
     * CONCEPT: ACCEPT - server channel is ready to accept a new connection.
     * This does NOT block because we checked isAcceptable() first.
     */
    private static void handleAccept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept(); // Non-blocking accept

        if (clientChannel == null) return; // Can happen with non-blocking

        clientChannel.configureBlocking(false); // Client must also be non-blocking!

        // Register client for READ events, attach a ByteBuffer for reading
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        clientChannel.register(selector, SelectionKey.OP_READ, buffer);
        System.out.println("  Accepted connection from: " + clientChannel.getRemoteAddress());
    }

    /**
     * CONCEPT: READ - channel has data available.
     * Read what's available now (may not be the complete message).
     * This is the complexity of NIO: you must handle partial reads/writes.
     */
    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();

        buffer.clear();
        int bytesRead = channel.read(buffer); // Non-blocking: reads available bytes

        if (bytesRead == -1) {
            // Client closed connection
            System.out.println("  Client disconnected: " + channel.getRemoteAddress());
            closeKey(key);
            return;
        }

        if (bytesRead > 0) {
            buffer.flip(); // Switch from write mode to read mode
            byte[] bytes = new byte[buffer.limit()];
            buffer.get(bytes);
            String message = new String(bytes, StandardCharsets.UTF_8).trim();
            System.out.println("  Received: '" + message + "' from " + channel.getRemoteAddress());

            // Prepare response and register for WRITE
            String response = "ECHO: " + message + "\n";
            ByteBuffer responseBuffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
            key.attach(responseBuffer); // Replace read buffer with response buffer
            key.interestOps(SelectionKey.OP_WRITE); // Switch interest to WRITE
        }
    }

    /**
     * CONCEPT: WRITE - channel is ready to accept writes.
     * Always check if write was complete (channel.write may not write all bytes).
     */
    private static void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();

        channel.write(buffer); // Write as many bytes as channel can accept

        if (!buffer.hasRemaining()) {
            // All bytes written - switch back to READ mode
            buffer.clear();
            key.attach(ByteBuffer.allocate(1024)); // Fresh read buffer
            key.interestOps(SelectionKey.OP_READ);
        }
        // If buffer still has bytes, keep OP_WRITE interest (partial write)
    }

    private static void closeKey(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException ignored) {}
    }

    /**
     * Demonstration: multiple NIO clients connecting to our server
     */
    private static void demonstrateClients() throws IOException, InterruptedException {
        System.out.println("\n--- Connecting NIO Clients ---");

        ExecutorService clientPool = Executors.newFixedThreadPool(5);
        int numClients = 5;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(numClients);

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            clientPool.submit(() -> {
                try {
                    SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", PORT));
                    String message = "Hello from client " + clientId;
                    client.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));

                    // Read response
                    ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
                    Thread.sleep(100);
                    client.read(responseBuffer);
                    responseBuffer.flip();
                    String response = StandardCharsets.UTF_8.decode(responseBuffer).toString().trim();
                    System.out.println("  Client " + clientId + " got: '" + response + "'");
                    client.close();
                } catch (Exception e) {
                    System.err.println("  Client " + clientId + " error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        clientPool.shutdown();
    }

    private static void explainNioComponents() {
        System.out.println("\n--- NIO Architecture Summary ---");
        System.out.println("Buffer types:");
        System.out.println("  HeapByteBuffer:   On JVM heap, GC managed");
        System.out.println("  DirectByteBuffer: Off-heap, OS memory, zero-copy I/O");
        System.out.println("  MappedByteBuffer: Memory-mapped file access");
        System.out.println();
        System.out.println("Buffer modes (flip() switches between them):");
        System.out.println("  Write mode: position=write-cursor, limit=capacity");
        System.out.println("  Read mode:  position=read-cursor, limit=bytes-written");
        System.out.println("  flip():     limit=position, position=0 (prepare to read what was written)");
        System.out.println("  clear():    position=0, limit=capacity (prepare to write)");
        System.out.println("  compact():  copy unread bytes to start, prepare to write more");
        System.out.println();
        System.out.println("Java 21 alternative: Virtual Threads with blocking I/O");
        System.out.println("  Thread.ofVirtual().start(() -> { /* blocking socket code here */ })");
        System.out.println("  Virtual threads block the VT (not OS thread) during I/O waits.");
        System.out.println("  Simpler code than NIO, similar scalability for I/O-bound servers.");
    }
}
