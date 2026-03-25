package com.distrisync.client;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Near-zero-latency remote-presence layer using UDP multicast.
 *
 * <h2>Send path</h2>
 * A {@link ScheduledExecutorService} fires every {@value #SEND_INTERVAL_MS} ms.
 * If the mouse has moved since the last tick the tracker encodes a
 * {@code UDP_POINTER|<clientId>|<x>|<y>} datagram and sends it to the
 * configured multicast group.  The dirty flag is cleared on each send cycle,
 * so static cursors produce zero traffic.
 *
 * <h2>Receive path</h2>
 * A dedicated daemon thread blocks on {@link MulticastSocket#receive} with a
 * {@value #RECEIVE_TIMEOUT_MS} ms socket timeout so it can check the
 * {@code running} flag and exit cleanly on shutdown.  Each valid packet updates
 * the {@link CursorState} for the originating client ID.
 *
 * <h2>Cursor rendering</h2>
 * {@link #renderCursors} is called from the JavaFX {@code AnimationTimer} on
 * the Application Thread — no locking is required.  Each cursor is rendered as
 * a semi-transparent filled circle with a contrasting label showing the
 * short client ID.  Opacity fades linearly from
 * {@value #FADE_START_MS} ms to {@value #CURSOR_TIMEOUT_MS} ms after the last
 * received packet; cursors older than {@value #CURSOR_TIMEOUT_MS} ms are
 * removed from the map.
 *
 * <h2>Graceful degradation</h2>
 * If multicast is unavailable (e.g., restricted corporate networks, VPNs) the
 * tracker logs a warning and disables itself — the rest of the application
 * continues to function normally.
 */
public final class UdpPointerTracker {

    private static final Logger log = LoggerFactory.getLogger(UdpPointerTracker.class);

    // ── network config ────────────────────────────────────────────────────────
    /** Administratively-scoped multicast group (RFC 2365). */
    private static final String MULTICAST_GROUP  = "239.255.42.42";
    private static final int    MULTICAST_PORT   = 9292;
    private static final int    RECEIVE_TIMEOUT_MS = 200;

    // ── timing ────────────────────────────────────────────────────────────────
    /** How often we broadcast our position when the mouse is moving. */
    private static final long SEND_INTERVAL_MS  = 50;
    /** Cursor starts fading after this many ms without a packet. */
    private static final long FADE_START_MS     = 300;
    /** Cursor is fully removed after this many ms without a packet. */
    private static final long CURSOR_TIMEOUT_MS = 500;

    // ── wire protocol ─────────────────────────────────────────────────────────
    private static final String MAGIC = "UDP_POINTER";

    // ── identity ──────────────────────────────────────────────────────────────
    /** Short 8-character prefix of a random UUID — unique enough for display. */
    private final String clientId = UUID.randomUUID().toString().substring(0, 8);

    // ── mutable mouse position (written from FX thread, read from send thread) ──
    private volatile double mouseX;
    private volatile double mouseY;
    /** Set when the mouse moves; cleared on each send tick. */
    private final AtomicBoolean moved = new AtomicBoolean(false);

    // ── remote cursor state ───────────────────────────────────────────────────
    private final ConcurrentHashMap<String, CursorState> remoteCursors = new ConcurrentHashMap<>();

    // ── networking ────────────────────────────────────────────────────────────
    private MulticastSocket          socket;
    private InetAddress              groupAddress;
    private NetworkInterface         networkInterface;
    private ScheduledExecutorService sendScheduler;
    private Thread                   receiveThread;

    private volatile boolean running;

    // ── canvas reference (used to clamp cursor coords to canvas bounds) ───────
    @SuppressWarnings("unused")
    private final Canvas canvas;

    // =========================================================================
    // Construction
    // =========================================================================

    public UdpPointerTracker(Canvas canvas) {
        this.canvas = canvas;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Opens the multicast socket, joins the group, and starts the send
     * scheduler and receive thread.  Failures are logged and silently ignored
     * so the application can run without pointer presence.
     */
    public void start() {
        running = true;
        try {
            groupAddress     = InetAddress.getByName(MULTICAST_GROUP);
            networkInterface = resolveNetworkInterface();

            socket = new MulticastSocket(MULTICAST_PORT);
            socket.setReuseAddress(true);
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            // Prevent our own packets from looping back and appearing as a remote cursor
            socket.setLoopbackMode(true);

            if (networkInterface != null) {
                socket.joinGroup(new InetSocketAddress(groupAddress, MULTICAST_PORT), networkInterface);
                log.info("UdpPointerTracker joined multicast group {}:{} via {}",
                        MULTICAST_GROUP, MULTICAST_PORT, networkInterface.getName());
            } else {
                // Fallback — let the OS pick an interface (less reliable, but better than nothing)
                socket.joinGroup(groupAddress);
                log.info("UdpPointerTracker joined multicast group {}:{} (OS-selected interface)",
                        MULTICAST_GROUP, MULTICAST_PORT);
            }

            startSendScheduler();
            startReceiveThread();

            log.info("UdpPointerTracker ready (clientId={})", clientId);

        } catch (Exception e) {
            log.warn("UdpPointerTracker: multicast unavailable — pointer presence disabled. Reason: {}",
                    e.getMessage());
            running = false;
        }
    }

    /**
     * Cancels the send scheduler, closes the socket, and interrupts the
     * receive thread.  Safe to call from any thread, including the FX thread.
     */
    public void stop() {
        running = false;

        if (sendScheduler != null) {
            sendScheduler.shutdownNow();
        }

        if (socket != null && !socket.isClosed()) {
            try {
                if (networkInterface != null && groupAddress != null) {
                    socket.leaveGroup(new InetSocketAddress(groupAddress, MULTICAST_PORT), networkInterface);
                } else if (groupAddress != null) {
                    socket.leaveGroup(groupAddress);
                }
            } catch (Exception ignored) {}
            socket.close();
        }

        log.debug("UdpPointerTracker stopped");
    }

    // =========================================================================
    // Mouse position sink  (called from FX Application Thread)
    // =========================================================================

    /**
     * Records the current mouse position and marks it as changed.
     * Called by {@link WhiteboardApp} on every {@code mouseMoved} /
     * {@code mouseDragged} event — this method must be cheap.
     */
    public void onMouseMoved(double x, double y) {
        mouseX = x;
        mouseY = y;
        moved.set(true);
    }

    // =========================================================================
    // Rendering  (called from FX Application Thread inside AnimationTimer)
    // =========================================================================

    /**
     * Renders all known remote cursors onto the supplied {@link GraphicsContext}.
     * Must be called exclusively from the JavaFX Application Thread.
     *
     * <p>Expired cursors (last seen more than {@value #CURSOR_TIMEOUT_MS} ms ago)
     * are evicted lazily during this call to avoid a separate cleanup thread.
     */
    public void renderCursors(GraphicsContext gc) {
        long now = System.currentTimeMillis();

        remoteCursors.values().removeIf(c -> now - c.lastSeen > CURSOR_TIMEOUT_MS + 300L);

        for (CursorState cursor : remoteCursors.values()) {
            long elapsed = now - cursor.lastSeen;
            if (elapsed >= CURSOR_TIMEOUT_MS) continue;

            double opacity = computeOpacity(elapsed);
            renderSingleCursor(gc, cursor, opacity);
        }
    }

    // =========================================================================
    // Send scheduler
    // =========================================================================

    private void startSendScheduler() {
        sendScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "udp-pointer-sender");
            t.setDaemon(true);
            return t;
        });
        sendScheduler.scheduleAtFixedRate(
                this::maybeBroadcastPointer,
                SEND_INTERVAL_MS, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    /**
     * Sends a {@code UDP_POINTER} datagram if the mouse has moved since the
     * last tick.  Skips the tick entirely if the socket is unavailable or if
     * no movement was recorded — keeping the network completely silent for
     * idle clients.
     */
    private void maybeBroadcastPointer() {
        if (!moved.getAndSet(false)) return;
        if (socket == null || socket.isClosed()) return;

        double x = mouseX;
        double y = mouseY;

        try {
            String payload = MAGIC + "|" + clientId + "|" + x + "|" + y;
            byte[] data    = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length, groupAddress, MULTICAST_PORT);
            socket.send(pkt);
        } catch (Exception e) {
            if (running) log.trace("UDP send error: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Receive thread
    // =========================================================================

    private void startReceiveThread() {
        receiveThread = new Thread(this::receiveLoop, "udp-pointer-receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private void receiveLoop() {
        byte[]         buf = new byte[256];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running) {
            try {
                pkt.setLength(buf.length);
                socket.receive(pkt);
                String msg = new String(buf, 0, pkt.getLength(), StandardCharsets.UTF_8);
                parseAndUpdateCursor(msg);
            } catch (SocketTimeoutException ignored) {
                // Expected — lets us re-check the running flag
            } catch (Exception e) {
                if (running) log.trace("UDP receive error: {}", e.getMessage());
            }
        }
    }

    /**
     * Parses a {@code UDP_POINTER|<id>|<x>|<y>} message and upserts the
     * remote cursor map.  Own packets are silently discarded.
     */
    private void parseAndUpdateCursor(String msg) {
        String[] parts = msg.split("\\|", 4);
        if (parts.length != 4 || !MAGIC.equals(parts[0])) return;

        String senderId = parts[1];
        if (clientId.equals(senderId)) return;   // echo of our own packet

        try {
            double x = Double.parseDouble(parts[2]);
            double y = Double.parseDouble(parts[3]);

            remoteCursors.compute(senderId, (id, existing) -> {
                if (existing == null) existing = new CursorState(id);
                existing.x        = x;
                existing.y        = y;
                existing.lastSeen = System.currentTimeMillis();
                return existing;
            });
        } catch (NumberFormatException e) {
            log.debug("Malformed UDP_POINTER packet ignored: {}", msg);
        }
    }

    // =========================================================================
    // Cursor rendering helpers
    // =========================================================================

    private void renderSingleCursor(GraphicsContext gc, CursorState cursor, double opacity) {
        gc.save();
        gc.setGlobalAlpha(opacity);

        Color fill   = Color.web(cursor.fillHex,   0.45);
        Color stroke = Color.web(cursor.strokeHex, 0.90);

        // ── cursor circle ─────────────────────────────────────────────────────
        gc.setFill(fill);
        gc.fillOval(cursor.x - 9, cursor.y - 9, 18, 18);

        gc.setStroke(stroke);
        gc.setLineWidth(1.5);
        gc.strokeOval(cursor.x - 9, cursor.y - 9, 18, 18);

        // ── crosshair dot ─────────────────────────────────────────────────────
        gc.setFill(stroke);
        gc.fillOval(cursor.x - 2, cursor.y - 2, 4, 4);

        // ── label ─────────────────────────────────────────────────────────────
        gc.setFont(Font.font("System", FontWeight.BOLD, 10));

        // Subtle shadow
        gc.setFill(Color.rgb(0, 0, 0, 0.55));
        gc.fillText(cursor.id, cursor.x + 12, cursor.y);

        gc.setFill(Color.WHITE);
        gc.fillText(cursor.id, cursor.x + 11, cursor.y - 1);

        gc.restore();
    }

    /**
     * Returns an opacity value in [0, 1]:
     * <ul>
     *   <li>1.0 while {@code elapsed} < {@value #FADE_START_MS}</li>
     *   <li>Linear ramp from 1.0 → 0.0 between {@value #FADE_START_MS}
     *       and {@value #CURSOR_TIMEOUT_MS}</li>
     * </ul>
     */
    private static double computeOpacity(long elapsed) {
        if (elapsed <= FADE_START_MS) return 1.0;
        double t = (double) (elapsed - FADE_START_MS) / (CURSOR_TIMEOUT_MS - FADE_START_MS);
        return Math.max(0.0, 1.0 - t);
    }

    // =========================================================================
    // Network interface resolution
    // =========================================================================

    private static NetworkInterface resolveNetworkInterface() {
        try {
            return NetworkInterface.networkInterfaces()
                .filter(ni -> {
                    try {
                        return ni.isUp() && !ni.isLoopback() && ni.supportsMulticast();
                    } catch (SocketException e) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
        } catch (SocketException e) {
            return null;
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** Returns the short client ID broadcast in every UDP_POINTER packet. */
    public String getClientId() {
        return clientId;
    }

    // =========================================================================
    // CursorState
    // =========================================================================

    /** Mutable per-peer cursor record — all writes are from the receive thread,
     *  all reads are from the FX Application Thread (rendering). */
    private static final class CursorState {

        final String id;
        volatile double x;
        volatile double y;
        volatile long   lastSeen;

        /** Deterministic color derived from the client ID string hash. */
        final String fillHex;
        final String strokeHex;

        CursorState(String id) {
            this.id       = id;
            this.lastSeen = System.currentTimeMillis();

            int hash = id.hashCode();
            // Bias toward mid-to-high brightness so cursors are visible on white canvas
            int r = clampBright((hash >> 16) & 0xFF);
            int g = clampBright((hash >>  8) & 0xFF);
            int b = clampBright( hash        & 0xFF);

            this.fillHex   = String.format("#%02X%02X%02X", r, g, b);
            // Slightly darker stroke for contrast
            this.strokeHex = String.format("#%02X%02X%02X",
                (int)(r * 0.75), (int)(g * 0.75), (int)(b * 0.75));
        }

        /** Ensures the channel value is visible on a white background. */
        private static int clampBright(int v) {
            // Keep values in [80, 200] — vivid but not washed-out
            return 80 + (v % 121);
        }
    }
}
