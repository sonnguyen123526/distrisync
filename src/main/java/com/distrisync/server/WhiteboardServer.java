package com.distrisync.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Entry point for the DistriSync server node.
 *
 * <p>Instantiates a {@link WalManager}, hands it to a {@link RoomManager},
 * and passes both to a {@link NioServer} which binds a TCP port and runs the
 * NIO event loop on the calling thread.  Rooms are created on demand when the
 * first client joins with a given {@code roomId}; the default room is named
 * {@code "Global"}.
 *
 * <h2>Durable WAL</h2>
 * All state-mutating messages ({@code MUTATION}, {@code SHAPE_DELETE},
 * {@code CLEAR_USER_SHAPES}) are appended to per-room WAL files under
 * {@code ./distrisync-data/} before being broadcast.  On restart the
 * {@link WalManager} replays each room's WAL into its
 * {@link CanvasStateManager} so that reconnecting clients receive a fully
 * recovered {@code SNAPSHOT}.
 *
 * <p>The default port is {@code 9090}; pass a single numeric argument to
 * override it, e.g.:
 * <pre>
 *   java -cp distrisync.jar com.distrisync.server.WhiteboardServer 9091
 * </pre>
 */
public class WhiteboardServer {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardServer.class);

    private static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.error("Invalid port argument '{}' — using default {}", args[0], DEFAULT_PORT);
            }
        }

        log.info("DistriSync WhiteboardServer initialising  port={}", port);

        WalManager walManager;
        try {
            walManager = new WalManager();
        } catch (IOException e) {
            log.error("Failed to initialise WalManager — cannot start: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        RoomManager roomManager = new RoomManager(walManager);
        NioServer   server      = new NioServer(port, roomManager);

        // Release WAL file handles on clean shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — closing WalManager and interrupting event loop");
            walManager.close();
            Thread.currentThread().interrupt();
        }, "shutdown-hook"));

        server.run();
    }
}
