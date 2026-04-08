package com.distrisync.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the DistriSync collaborative whiteboard server.
 *
 * <h2>Usage</h2>
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.distrisync.server.WhiteboardServer
 *   mvn exec:java -Dexec.mainClass=com.distrisync.server.WhiteboardServer \
 *       -Dexec.args="9090 ./data/wal"
 * </pre>
 *
 * <h2>Arguments (positional, all optional)</h2>
 * <ol>
 *   <li>{@code port}    — TCP port to listen on (default: {@value #DEFAULT_PORT})</li>
 *   <li>{@code dataDir} — directory for WAL files   (default: {@value #DEFAULT_DATA_DIR})</li>
 * </ol>
 *
 * <h2>Startup sequence</h2>
 * <ol>
 *   <li>Open {@link WalManager} on {@code dataDir}.</li>
 *   <li>Create a WAL-backed {@link RoomManager}.</li>
 *   <li>Start {@link StorageLifecycleManager} GC daemon.</li>
 *   <li>Run {@link NioServer} on the configured port (blocks the main thread).</li>
 * </ol>
 *
 * <p>A JVM shutdown hook closes the WAL manager gracefully so no partial frames
 * are left on disk.
 */
public final class WhiteboardServer {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardServer.class);

    static final int    DEFAULT_PORT     = 9090;
    static final String DEFAULT_DATA_DIR = "data/wal";

    private WhiteboardServer() {}

    public static void main(String[] args) {
        int    port    = DEFAULT_PORT;
        String dataDir = DEFAULT_DATA_DIR;

        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.error("Invalid port '{}' — using default {}", args[0], DEFAULT_PORT);
            }
        }
        if (args.length >= 2) {
            dataDir = args[1];
        }

        log.info("DistriSync WhiteboardServer starting  port={} dataDir='{}'", port, dataDir);

        Path walDir = Paths.get(dataDir);
        WalManager walManager;
        try {
            walManager = new WalManager(walDir);
        } catch (IOException e) {
            log.error("Failed to initialise WalManager at '{}': {}", walDir, e.getMessage(), e);
            System.exit(1);
            return;
        }

        RoomManager            roomManager = new RoomManager(walManager);
        StorageLifecycleManager lifecycle  = new StorageLifecycleManager(roomManager, walManager);
        NioServer               server     = new NioServer(port, roomManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook — stopping server and flushing WAL");
            server.stop();
            lifecycle.shutdown();
            walManager.close();
        }, "shutdown-hook"));

        server.run(); // blocks until stopped or interrupted
    }
}
