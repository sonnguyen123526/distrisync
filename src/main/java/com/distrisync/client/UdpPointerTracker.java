package com.distrisync.client;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 * {@code UDP_POINTER|<clientId>|<x>|<y>} datagram and multicasts it.
 * The dirty flag is cleared on each tick so static cursors generate zero traffic.
 *
 * <h2>Receive path</h2>
 * A dedicated daemon thread blocks on {@link MulticastSocket#receive} with a
 * {@value #RECEIVE_TIMEOUT_MS} ms timeout so it can honour the {@code running}
 * flag on shutdown.  Every valid packet is handed to the FX Application Thread
 * via {@link Platform#runLater} — <em>no JavaFX node is ever touched from the
 * receive thread</em>.
 *
 * <h2>Cursor rendering</h2>
 * Remote cursors are rendered as JavaFX {@link Group} nodes (a coloured
 * {@link Circle} + a {@link Label} badge) added to a transparent overlay
 * {@link Pane}.  Positions are updated by relocating the Group via
 * {@code setLayoutX/Y} — all on the FX thread.
 *
 * <h2>Fade-out / cleanup</h2>
 * A periodic cleanup task (dispatched via {@code Platform.runLater} every
 * {@value #CLEANUP_INTERVAL_MS} ms) checks whether any peer's last packet is
 * older than {@value #CURSOR_TIMEOUT_MS} ms and, if so, plays a
 * {@link FadeTransition} before removing the node.  A new packet for the same
 * peer cancels any in-progress fade.
 *
 * <h2>Graceful degradation</h2>
 * If multicast is unavailable the tracker logs a warning and disables itself;
 * the rest of the application continues normally.
 */
public final class UdpPointerTracker {

    private static final Logger log = LoggerFactory.getLogger(UdpPointerTracker.class);

    // ── network config ────────────────────────────────────────────────────────
    private static final String MULTICAST_GROUP    = "239.255.42.42";
    private static final int    MULTICAST_PORT     = 9292;
    private static final int    RECEIVE_TIMEOUT_MS = 200;

    // ── timing ────────────────────────────────────────────────────────────────
    private static final long SEND_INTERVAL_MS    = 50;
    private static final long CURSOR_TIMEOUT_MS   = 500;
    private static final long FADE_DURATION_MS    = 250;
    private static final long CLEANUP_INTERVAL_MS = 100;

    // ── wire protocol ─────────────────────────────────────────────────────────
    private static final String MAGIC = "UDP_POINTER";

    // ── identity ──────────────────────────────────────────────────────────────
    private final String clientId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Human-readable display name shown in remote cursor badges.
     * Set via {@link #setAuthorName(String)} before calling {@link #start()}.
     * Included in every outgoing datagram so peers can display the real name.
     */
    private volatile String authorName = "";

    // ── mouse position (written from FX thread, read from send scheduler) ─────
    private volatile double mouseX;
    private volatile double mouseY;
    private final AtomicBoolean moved = new AtomicBoolean(false);

    // ── cursor nodes — only ever accessed on the FX Application Thread ────────
    private final Map<String, CursorEntry> cursors = new HashMap<>();

    // ── UI overlay ────────────────────────────────────────────────────────────
    private final Pane cursorPane;

    // ── networking ────────────────────────────────────────────────────────────
    private MulticastSocket          socket;
    private InetAddress              groupAddress;
    private NetworkInterface         networkInterface;
    private ScheduledExecutorService sendScheduler;
    private ScheduledExecutorService cleanupScheduler;
    private Thread                   receiveThread;

    private volatile boolean running;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * @param cursorPane the transparent overlay {@link Pane} that sits on top
     *                   of the canvas stack; cursor nodes are added to / removed
     *                   from this pane exclusively on the FX Application Thread
     */
    public UdpPointerTracker(Pane cursorPane) {
        this.cursorPane = cursorPane;
    }

    /**
     * Sets the human-readable display name broadcast in every UDP datagram and
     * displayed in remote cursor badges.  Call before {@link #start()}.
     *
     * @param authorName the display name; {@code null} is treated as empty string
     */
    public void setAuthorName(String authorName) {
        this.authorName = authorName != null ? authorName : "";
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Opens the multicast socket, joins the group, and starts the send
     * scheduler, receive thread, and cursor-cleanup scheduler.
     * Failures are logged and silently absorbed so the app can run without
     * peer-presence support.
     */
    public void start() {
        running = true;
        try {
            groupAddress     = InetAddress.getByName(MULTICAST_GROUP);
            networkInterface = resolveNetworkInterface();

            socket = new MulticastSocket(MULTICAST_PORT);
            socket.setReuseAddress(true);
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            // false = ENABLE loopback so packets sent on this machine are received
            // by the same socket (required for localhost testing).
            // Own-packet filtering is done via the clientId check in handleIncomingPacket.
            socket.setLoopbackMode(false);

            if (networkInterface != null) {
                socket.joinGroup(
                        new InetSocketAddress(groupAddress, MULTICAST_PORT),
                        networkInterface);
                log.info("UdpPointerTracker joined {}:{} via {}",
                        MULTICAST_GROUP, MULTICAST_PORT, networkInterface.getName());
            } else {
                socket.joinGroup(groupAddress);
                log.info("UdpPointerTracker joined {}:{} (OS-selected interface)",
                        MULTICAST_GROUP, MULTICAST_PORT);
            }

            startSendScheduler();
            startReceiveThread();
            startCleanupScheduler();

            log.info("UdpPointerTracker ready (clientId={})", clientId);

        } catch (Exception e) {
            log.warn("UdpPointerTracker: multicast unavailable — pointer presence disabled. Reason: {}",
                    e.getMessage());
            running = false;
        }
    }

    /**
     * Cancels all background threads, closes the socket, and removes all
     * cursor nodes from the pane on the FX thread.
     * Safe to call from any thread.
     */
    public void stop() {
        running = false;

        if (sendScheduler    != null) sendScheduler.shutdownNow();
        if (cleanupScheduler != null) cleanupScheduler.shutdownNow();

        if (socket != null && !socket.isClosed()) {
            try {
                if (networkInterface != null && groupAddress != null) {
                    socket.leaveGroup(
                            new InetSocketAddress(groupAddress, MULTICAST_PORT),
                            networkInterface);
                } else if (groupAddress != null) {
                    socket.leaveGroup(groupAddress);
                }
            } catch (Exception ignored) {}
            socket.close();
        }

        // Remove all cursor nodes on the FX thread
        Platform.runLater(() -> {
            cursorPane.getChildren().clear();
            cursors.clear();
        });

        log.debug("UdpPointerTracker stopped");
    }

    // =========================================================================
    // Mouse position sink  (FX Application Thread)
    // =========================================================================

    /**
     * Records the latest mouse position and marks it as dirty.
     * Must be cheap — called on every {@code mouseMoved} / {@code mouseDragged}.
     */
    public void onMouseMoved(double x, double y) {
        mouseX = x;
        mouseY = y;
        moved.set(true);
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
                SEND_INTERVAL_MS, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void maybeBroadcastPointer() {
        if (!moved.getAndSet(false)) return;
        if (socket == null || socket.isClosed()) return;

        double x = mouseX;
        double y = mouseY;

        try {
            // Wire format: UDP_POINTER|<clientId>|<x>|<y>|<authorName>
            // authorName may be empty but the field separator is always present.
            String payload = MAGIC + "|" + clientId + "|" + x + "|" + y + "|" + authorName;
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
                handleIncomingPacket(msg);
            } catch (SocketTimeoutException ignored) {
                // Expected — used as a running-flag poll interval
            } catch (Exception e) {
                if (running) log.trace("UDP receive error: {}", e.getMessage());
            }
        }
    }

    /**
     * Parses a {@code UDP_POINTER|<id>|<x>|<y>|<authorName>} datagram.
     * The {@code authorName} field (5th token) is optional for backward
     * compatibility with older clients that send only 4 tokens.
     * Own packets (loopback) are discarded.  All UI mutations are dispatched
     * to the FX Application Thread via {@link Platform#runLater}.
     */
    private void handleIncomingPacket(String msg) {
        // Split into at most 5 parts; authorName may itself contain no pipes.
        String[] parts = msg.split("\\|", 5);
        if (parts.length < 4 || !MAGIC.equals(parts[0])) return;

        String senderId   = parts[1];
        if (clientId.equals(senderId)) return;

        String peerName = parts.length >= 5 ? parts[4] : senderId;
        if (peerName.isBlank()) peerName = senderId;

        final String resolvedName = peerName;
        log.trace("UDP pointer from '{}' ({})", resolvedName, senderId);

        try {
            double x = Double.parseDouble(parts[2]);
            double y = Double.parseDouble(parts[3]);

            Platform.runLater(() -> updateCursor(senderId, resolvedName, x, y));

        } catch (NumberFormatException e) {
            log.debug("Malformed UDP_POINTER packet ignored: {}", msg);
        }
    }

    // =========================================================================
    // Cursor node management  (FX Application Thread only)
    // =========================================================================

    /**
     * Creates or updates the cursor node for the given peer, cancelling any
     * in-progress fade-out so the peer appears immediately at its new position.
     *
     * @param peerId      stable short client-ID (used as the map key)
     * @param displayName human-readable name shown in the badge; falls back to
     *                    {@code peerId} for legacy peers
     * @param x           canvas X coordinate
     * @param y           canvas Y coordinate
     */
    private void updateCursor(String peerId, String displayName, double x, double y) {
        CursorEntry entry = cursors.get(peerId);

        if (entry == null) {
            // First packet from this peer — create a new cursor node.
            Group node = buildCursorNode(peerId, displayName);
            node.setLayoutX(x);
            node.setLayoutY(y);
            entry = new CursorEntry(peerId, node);
            cursors.put(peerId, entry);
            cursorPane.getChildren().add(node);
        } else {
            // Cancel any active fade so the cursor snaps back to full opacity.
            if (entry.fadingOut && entry.activeFade != null) {
                entry.activeFade.stop();
                entry.activeFade = null;
                entry.node.setOpacity(1.0);
            }
            entry.fadingOut = false;
            entry.node.setLayoutX(x);
            entry.node.setLayoutY(y);
        }

        entry.lastSeen = System.currentTimeMillis();
    }

    /**
     * Builds a {@link Group} containing a coloured dot and a name badge for
     * the given peer.  Colours are deterministically derived from the peer's
     * client-ID hash so they remain stable across packets.
     *
     * @param peerId      stable short client-ID (used for colour derivation)
     * @param displayName human-readable label shown in the badge
     */
    private Group buildCursorNode(String peerId, String displayName) {
        int hash = peerId.hashCode();
        int r    = clampBright((hash >> 16) & 0xFF);
        int g    = clampBright((hash >>  8) & 0xFF);
        int b    = clampBright( hash        & 0xFF);

        Color fill   = Color.rgb(r, g, b, 0.55);
        Color stroke = Color.rgb((int)(r * 0.75), (int)(g * 0.75), (int)(b * 0.75), 0.90);

        Circle dot = new Circle(9, fill);
        dot.setStroke(stroke);
        dot.setStrokeWidth(1.5);

        Label badge = new Label(displayName);
        badge.setLayoutX(13);
        badge.setLayoutY(-8);
        badge.setStyle(
            "-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;" +
            "-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 3;" +
            "-fx-padding: 1 4;");

        return new Group(dot, badge);
    }

    /** Keeps colour channel values visible on a white canvas: range [80, 200]. */
    private static int clampBright(int v) {
        return 80 + (v % 121);
    }

    // =========================================================================
    // Cleanup scheduler  (background thread → Platform.runLater)
    // =========================================================================

    private void startCleanupScheduler() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "udp-cursor-cleanup");
            t.setDaemon(true);
            return t;
        });
        // Dispatch the actual check to the FX thread so we never touch nodes
        // from a background thread.
        cleanupScheduler.scheduleAtFixedRate(
                () -> Platform.runLater(this::expireOldCursors),
                CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Checks every tracked cursor for inactivity.  Must run on the FX thread.
     * If a peer has not sent a packet in more than {@value #CURSOR_TIMEOUT_MS} ms,
     * a {@link FadeTransition} is played; on completion the node is removed.
     */
    private void expireOldCursors() {
        long now = System.currentTimeMillis();

        for (CursorEntry entry : cursors.values()) {
            if (entry.fadingOut || now - entry.lastSeen <= CURSOR_TIMEOUT_MS) {
                continue;
            }

            entry.fadingOut = true;
            FadeTransition ft = new FadeTransition(Duration.millis(FADE_DURATION_MS), entry.node);
            ft.setFromValue(1.0);
            ft.setToValue(0.0);
            ft.setOnFinished(ev -> {
                cursorPane.getChildren().remove(entry.node);
                cursors.remove(entry.clientId);
            });
            entry.activeFade = ft;
            ft.play();
        }
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

    /** Returns the short client ID broadcast in every {@code UDP_POINTER} packet. */
    public String getClientId() {
        return clientId;
    }

    // =========================================================================
    // CursorEntry — per-peer mutable record (FX thread only)
    // =========================================================================

    private static final class CursorEntry {

        final String          clientId;
        final Group           node;
        long                  lastSeen;
        boolean               fadingOut;
        FadeTransition        activeFade;

        CursorEntry(String clientId, Group node) {
            this.clientId = clientId;
            this.node     = node;
            this.lastSeen = System.currentTimeMillis();
        }
    }
}
