package com.distrisync.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the DistriSync server node.
 *
 * <p>Instantiates a {@link CanvasStateManager} and hands it to a
 * {@link NioServer} which binds a TCP port and runs the NIO event loop on the
 * calling thread.
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

        CanvasStateManager stateManager = new CanvasStateManager();
        NioServer server = new NioServer(port, stateManager);

        // Install a shutdown hook so Ctrl-C logs a clean stop message.
        Thread eventLoop = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — interrupting event loop");
            eventLoop.interrupt();
        }, "shutdown-hook"));

        server.run();
    }
}
