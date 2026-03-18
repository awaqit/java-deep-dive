package com.deepdive.month01.week04;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Week 4: NIO File I/O
 *
 * CONCEPT: Java NIO provides three ways to do file I/O:
 *
 * 1. java.nio.file.Files (high-level, blocking):
 *    - Simple API, internally uses NIO
 *    - Good for most use cases with virtual threads
 *
 * 2. AsynchronousFileChannel (async, completion-based or Future-based):
 *    - Non-blocking: register callback, get notified when I/O completes
 *    - Good for I/O-bound servers without virtual threads
 *    - Implemented via OS async I/O (io_uring on Linux, IOCP on Windows)
 *
 * 3. MappedByteBuffer (memory-mapped files):
 *    - File is mapped into process address space
 *    - OS handles paging - only touched pages are loaded
 *    - Extremely fast for random access and large files
 *    - Used by databases, JVM class loading, etc.
 *
 * Performance hierarchy (fastest first for large files):
 * - MappedByteBuffer (zero-copy, OS page cache)
 * - FileChannel with direct ByteBuffer (OS -> direct memory)
 * - FileChannel with heap ByteBuffer (OS -> direct mem -> heap)
 * - Files.readAllBytes() (convenience wrapper)
 */
public class NioFileDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== NIO File I/O Demo ===");

        Path tempDir = Files.createTempDirectory("nio-demo");
        try {
            demonstrateAsyncFileChannel(tempDir);
            demonstrateMemoryMappedFile(tempDir);
            demonstrateFileChannelCopy(tempDir);
            demonstrateFileWatcher(tempDir);
        } finally {
            // Cleanup
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        }
    }

    /**
     * CONCEPT: AsynchronousFileChannel - two usage patterns:
     *
     * Pattern 1: CompletionHandler (callback-based)
     *   channel.read(buffer, position, attachment, completionHandler)
     *   - Callback invoked by I/O thread pool when complete
     *   - Beware: callback on different thread, handle exceptions
     *
     * Pattern 2: Future (blocking get, but non-blocking I/O)
     *   Future<Integer> f = channel.read(buffer, position)
     *   f.get() // blocks only the calling thread, not an I/O thread
     *
     * WHY: Async file I/O matters when you have many concurrent file operations
     * and can't afford blocking threads (pre-virtual-threads world).
     */
    private static void demonstrateAsyncFileChannel(Path dir) throws Exception {
        System.out.println("\n--- AsynchronousFileChannel ---");

        Path file = dir.resolve("async-test.txt");
        String content = "Hello, Async NIO! Line " + java.util.stream.IntStream
                .range(1, 50).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";

        // ASYNC WRITE via Future
        try (AsynchronousFileChannel writeChannel = AsynchronousFileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            ByteBuffer writeBuffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            Future<Integer> writeFuture = writeChannel.write(writeBuffer, 0);
            int bytesWritten = writeFuture.get(); // Block for demo purposes
            System.out.printf("Async write: %d bytes written to %s%n", bytesWritten, file.getFileName());
        }

        // ASYNC READ via CompletionHandler (callback)
        CountDownLatch readLatch = new CountDownLatch(1);
        StringBuilder readResult = new StringBuilder();

        try (AsynchronousFileChannel readChannel = AsynchronousFileChannel.open(
                file, StandardOpenOption.READ)) {

            long fileSize = readChannel.size();
            ByteBuffer readBuffer = ByteBuffer.allocate((int) fileSize);

            // CONCEPT: CompletionHandler - called when I/O completes or fails
            readChannel.read(readBuffer, 0, readBuffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer bytesRead, ByteBuffer attachment) {
                    attachment.flip();
                    readResult.append(StandardCharsets.UTF_8.decode(attachment));
                    System.out.printf("Async read callback: %d bytes read on thread: %s%n",
                            bytesRead, Thread.currentThread().getName());
                    readLatch.countDown();
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    System.err.println("Async read failed: " + exc.getMessage());
                    readLatch.countDown();
                }
            });

            System.out.println("Async read in progress... (non-blocking)");
            System.out.println("Main thread continues while I/O is in progress!");
            readLatch.await(5, TimeUnit.SECONDS);
        }

        System.out.printf("Read result preview: '%s...'%n",
                readResult.toString().replace("\n", " ").substring(0, Math.min(50, readResult.length())));
    }

    /**
     * CONCEPT: Memory-Mapped Files (MappedByteBuffer)
     *
     * How it works:
     * 1. OS maps file into virtual address space
     * 2. Accessing file memory may trigger page fault (first access)
     * 3. OS loads the needed page from disk
     * 4. Subsequent accesses are pure memory operations (no syscall!)
     *
     * Use cases:
     * - Large file random access (databases, indexes, log files)
     * - Shared memory between processes (IPC)
     * - Zero-copy file serving (read file -> write to socket)
     *
     * Limitations:
     * - Files must fit in virtual address space (not a problem on 64-bit)
     * - MappedByteBuffer can't be explicitly unmapped in Java (JDK-4724038)
     * - Direct memory not counted in heap metrics
     */
    private static void demonstrateMemoryMappedFile(Path dir) throws Exception {
        System.out.println("\n--- Memory-Mapped File (MappedByteBuffer) ---");

        Path file = dir.resolve("mmap-test.bin");
        int fileSize = 1024 * 1024; // 1 MB

        // Create file and write initial data using FileChannel
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {

            // Grow file to desired size
            channel.write(ByteBuffer.allocate(1), fileSize - 1);

            // CONCEPT: MapMode.READ_WRITE - changes to buffer are reflected in file
            MappedByteBuffer mmap = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);

            System.out.println("File mapped to memory: " + fileSize / 1024 + " KB");

            // Write via memory (no syscall after page fault)
            long writeStart = System.nanoTime();
            for (int i = 0; i < fileSize; i += 4) {
                mmap.putInt(i, i / 4); // Write index values
            }
            long writeTime = System.nanoTime() - writeStart;

            // Read via memory
            long readStart = System.nanoTime();
            long checksum = 0;
            mmap.position(0);
            for (int i = 0; i < fileSize / 4; i++) {
                checksum += mmap.getInt();
            }
            long readTime = System.nanoTime() - readStart;

            System.out.printf("MMap write (1MB): %,d µs%n", writeTime / 1000);
            System.out.printf("MMap read  (1MB): %,d µs%n", readTime / 1000);
            System.out.printf("Checksum: %d%n", checksum);

            // CONCEPT: force() ensures changes are flushed to disk
            // (without this, OS may hold pages in memory indefinitely)
            mmap.force();
        }

        // Verify data persisted
        try (FileChannel verifyChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            MappedByteBuffer verifyMap = verifyChannel.map(FileChannel.MapMode.READ_ONLY, 0, 16);
            System.out.print("First 4 integers in file: ");
            for (int i = 0; i < 4; i++) System.out.print(verifyMap.getInt() + " ");
            System.out.println();
        }
    }

    /**
     * CONCEPT: FileChannel with zero-copy via transferTo()
     *
     * Normal file copy:
     *   disk -> kernel buffer -> user buffer -> kernel buffer -> socket
     *   (4 copies, 4 context switches)
     *
     * Zero-copy with transferTo() on Linux (sendfile syscall):
     *   disk -> kernel buffer -> socket
     *   (2 copies, 2 context switches)
     *
     * WHY: This is how Kafka achieves high throughput - it uses sendfile to
     * transfer log segments from disk to network sockets without user-space copies.
     */
    private static void demonstrateFileChannelCopy(Path dir) throws IOException {
        System.out.println("\n--- FileChannel Zero-Copy (transferTo) ---");

        Path source = dir.resolve("source.bin");
        Path dest = dir.resolve("dest.bin");

        // Create source file
        byte[] data = new byte[512 * 1024]; // 512 KB
        new java.util.Random(42).nextBytes(data);
        Files.write(source, data);

        // Traditional copy (buffer-based)
        long bufferStart = System.nanoTime();
        try (FileChannel src = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(dest, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 64KB buffer
            while (src.read(buffer) > 0) {
                buffer.flip();
                dst.write(buffer);
                buffer.clear();
            }
        }
        long bufferTime = System.nanoTime() - bufferStart;

        // Zero-copy via transferTo
        Files.delete(dest);
        long zeroStart = System.nanoTime();
        try (FileChannel src = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(dest, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // CONCEPT: transferTo uses OS-level copy (sendfile on Linux)
            long transferred = src.transferTo(0, src.size(), dst);
            System.out.printf("Zero-copy transferred: %,d bytes%n", transferred);
        }
        long zeroTime = System.nanoTime() - zeroStart;

        System.out.printf("Buffer copy (512KB):     %,d µs%n", bufferTime / 1000);
        System.out.printf("Zero-copy transferTo:    %,d µs%n", zeroTime / 1000);
        System.out.println("NOTE: Zero-copy benefit most visible for large files and socket destinations.");
    }

    /**
     * CONCEPT: WatchService - file system event notification.
     * Instead of polling, the OS notifies the application of changes.
     *
     * WHY: Used for config hot-reloading (Spring Boot DevTools, Kubernetes ConfigMap updates),
     * build tools (Gradle continuous build), and IDE file watching.
     *
     * Underlying OS mechanisms:
     * - Linux: inotify
     * - macOS: kqueue / FSEvents
     * - Windows: ReadDirectoryChangesW
     */
    private static void demonstrateFileWatcher(Path dir) throws IOException, InterruptedException {
        System.out.println("\n--- WatchService (File System Events) ---");

        WatchService watcher = FileSystems.getDefault().newWatchService();

        // Register directory for CREATE, MODIFY, DELETE events
        dir.register(watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

        System.out.println("Watching directory: " + dir);

        // Background watcher thread
        Thread watchThread = new Thread(() -> {
            try {
                WatchKey key = watcher.poll(3, TimeUnit.SECONDS);
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        System.out.printf("  Event: %-10s -> %s%n",
                                event.kind().name(),
                                event.context());
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();

        // Trigger file events
        Path watchedFile = dir.resolve("watched.txt");
        Thread.sleep(100);
        Files.writeString(watchedFile, "initial content");  // CREATE + MODIFY
        Thread.sleep(100);
        Files.writeString(watchedFile, "updated content");  // MODIFY
        Thread.sleep(100);
        Files.delete(watchedFile);                          // DELETE

        watchThread.join(3000);
        watcher.close();
        System.out.println("NOTE: WatchService is polling-based on some OS/JVM combos. inotify preferred.");
    }
}
