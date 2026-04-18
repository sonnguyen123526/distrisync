package com.distrisync.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Push-to-talk capture and UDP playback for the DistriSync audio data plane.
 * Uses {@link javax.sound.sampled} at {@link #AUDIO_FORMAT}.
 */
public final class AudioEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioEngine.class);

    /**
     * Global wire / capture / playback format: 8 kHz, 16-bit signed PCM, mono, big-endian.
     */
    public static final AudioFormat AUDIO_FORMAT =
            new AudioFormat(8000.0f, 16, 1, true, true);

    private static final int UDP_IDENTITY_BYTES = 36;
    /** 10 ms of PCM at {@link #AUDIO_FORMAT}: 8000 Hz × 2 bytes × 0.01 s = 160 bytes. */
    public static final int PAYLOAD_SIZE = 160;
    /**
     * javax.sound sampled internal buffer: 10 frames × {@link #PAYLOAD_SIZE} = 1 600 bytes
     * to keep the OS/driver queue shallow (≈100 ms at this frame size).
     */
    private static final int LINE_BUFFER_BYTES = 10 * PAYLOAD_SIZE;
    /** Wire datagram: 36-byte identity + {@link #PAYLOAD_SIZE} audio = 196 bytes. */
    private static final int UDP_PACKET_BYTES = UDP_IDENTITY_BYTES + PAYLOAD_SIZE;
    /** Completes a big-endian 16-bit sample when {@link TargetDataLine#read} returns an odd byte count. */
    private static final byte[] PCM_LOW_SCRATCH = new byte[1];

    private final ReentrantLock udpLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean receiveLoopStarted = new AtomicBoolean(false);

    private volatile DatagramSocket datagramSocket;
    /** UTF-8 token or identity prefix, always length {@value #UDP_IDENTITY_BYTES}. */
    private volatile byte[] udpIdentityBytes = new byte[UDP_IDENTITY_BYTES];

    private SourceDataLine playbackLine;
    private final Object playbackLock = new Object();

    private volatile TargetDataLine captureLine;
    private volatile Thread captureThread;
    private volatile Thread receiveThread;

    private volatile UserSpeakingListener userSpeakingListener;

    /** Last 36-byte relay header (selector of {@link #rxSpeakerIdCached}). */
    private final byte[] rxIdentityMatch = new byte[UDP_IDENTITY_BYTES];
    private boolean rxSpeakerIdCacheValid;
    private String rxSpeakerIdCached = "";

    public void setUserSpeakingListener(UserSpeakingListener listener) {
        this.userSpeakingListener = listener;
    }

    /**
     * Stores the admission token, opens a {@link DatagramSocket}, connects it to
     * the server, sends a 36-byte registration datagram (token only), and starts
     * the receive daemon on first admission.
     */
    public void onUdpAdmission(String serverHost, int serverUdpPort, String udpToken) throws IOException {
        if (closed.get()) {
            return;
        }
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("serverHost must not be blank");
        }
        if (serverUdpPort < 1 || serverUdpPort > 65_535) {
            throw new IllegalArgumentException("Invalid UDP port: " + serverUdpPort);
        }
        if (udpToken == null || udpToken.isBlank()) {
            throw new IllegalArgumentException("udpToken must not be blank");
        }

        stopRecording();

        byte[] identity = utf8Fixed36(udpToken);
        InetAddress address = InetAddress.getByName(serverHost.strip());

        udpLock.lock();
        try {
            udpIdentityBytes = identity;

            DatagramSocket old = datagramSocket;
            if (old != null && !old.isClosed()) {
                try {
                    old.close();
                } catch (Exception ignored) {
                    /* best-effort */
                }
            }

            DatagramSocket ds = new DatagramSocket();
            ds.connect(address, serverUdpPort);
            datagramSocket = ds;

            DatagramPacket punch = new DatagramPacket(identity, UDP_IDENTITY_BYTES);
            ds.send(punch);
            log.debug("UDP registration punch sent ({} bytes) to {}:{}",
                    UDP_IDENTITY_BYTES, serverHost, serverUdpPort);
        } finally {
            udpLock.unlock();
        }

        ensureReceiveDaemon();
    }

    /**
     * Starts the permanent UDP receive thread once (no-op if already running).
     * Safe to call from {@link NetworkClient#connect()} before {@code UDP_ADMISSION}.
     */
    public void startReceiveDaemon() {
        ensureReceiveDaemon();
    }

    private void ensureReceiveDaemon() {
        if (!receiveLoopStarted.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::receiveLoop, "distrisync-audio-recv");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Audio receive thread terminated", ex));
        receiveThread = t;
        t.start();
    }

    /**
     * Captures microphone PCM and sends {@code [36-byte udpToken][pcm...]} datagrams
     * to the server relay.
     */
    public void startRecording() throws LineUnavailableException {
        if (closed.get()) {
            throw new IllegalStateException("AudioEngine is closed");
        }
        if (!recording.compareAndSet(false, true)) {
            return;
        }

        DatagramSocket sock;
        udpLock.lock();
        try {
            sock = datagramSocket;
        } finally {
            udpLock.unlock();
        }
        if (sock == null || sock.isClosed() || !sock.isConnected()) {
            recording.set(false);
            throw new IllegalStateException("UDP not admitted — wait for UDP_ADMISSION");
        }

        TargetDataLine line = AudioSystem.getTargetDataLine(AUDIO_FORMAT);
        line.open(AUDIO_FORMAT, LINE_BUFFER_BYTES);
        line.start();
        captureLine = line;

        Thread cap = new Thread(this::captureLoop, "distrisync-audio-capture");
        cap.setDaemon(true);
        cap.setPriority(Thread.MAX_PRIORITY);
        captureThread = cap;
        cap.start();
    }

    /**
     * Stops the capture loop and releases the {@link TargetDataLine}.
     */
    public void stopRecording() {
        recording.set(false);
        TargetDataLine line = captureLine;
        captureLine = null;
        if (line != null) {
            try {
                line.stop();
            } catch (Exception ignored) {
                /* best-effort */
            }
            try {
                line.close();
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRecording() {
        return recording.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopRecording();

        udpLock.lock();
        try {
            DatagramSocket ds = datagramSocket;
            datagramSocket = null;
            if (ds != null && !ds.isClosed()) {
                try {
                    ds.close();
                } catch (Exception ignored) {
                    /* best-effort */
                }
            }
        } finally {
            udpLock.unlock();
        }

        synchronized (playbackLock) {
            if (playbackLine != null) {
                try {
                    playbackLine.stop();
                } catch (Exception ignored) {
                    /* best-effort */
                }
                try {
                    playbackLine.close();
                } catch (Exception ignored) {
                    /* best-effort */
                }
                playbackLine = null;
            }
        }

        Thread rt = receiveThread;
        if (rt != null) {
            try {
                rt.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void captureLoop() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        byte[] buffer = new byte[UDP_PACKET_BYTES];
        System.arraycopy(udpIdentityBytes, 0, buffer, 0, UDP_IDENTITY_BYTES);
        DatagramPacket packet = new DatagramPacket(buffer, UDP_PACKET_BYTES);

        while (recording.get() && !closed.get()) {
            TargetDataLine line = captureLine;
            if (line == null) {
                break;
            }
            int off = UDP_IDENTITY_BYTES;
            int remaining = PAYLOAD_SIZE;
            int pendingHigh = -1;
            while (remaining > 0 && recording.get() && !closed.get()) {
                int needBytes = pendingHigh >= 0 ? 1 : remaining;
                while (line.available() < needBytes && recording.get() && !closed.get()) {
                    Thread.yield();
                }
                if (!recording.get() || closed.get()) {
                    break;
                }
                int n;
                try {
                    if (pendingHigh >= 0) {
                        n = line.read(PCM_LOW_SCRATCH, 0, 1);
                        if (n < 0) {
                            recording.set(false);
                            break;
                        }
                        if (n == 0) {
                            continue;
                        }
                        buffer[off] = (byte) pendingHigh;
                        buffer[off + 1] = PCM_LOW_SCRATCH[0];
                        pendingHigh = -1;
                        off += 2;
                        remaining -= 2;
                        continue;
                    }
                    n = line.read(buffer, off, remaining);
                } catch (Exception e) {
                    log.debug("Mic read ended: {}", e.getMessage());
                    recording.set(false);
                    break;
                }
                if (n < 0) {
                    recording.set(false);
                    break;
                }
                if (n == 0) {
                    continue;
                }
                if ((n & 1) != 0) {
                    pendingHigh = buffer[off + n - 1] & 0xFF;
                    n--;
                }
                if (n == 0) {
                    continue;
                }
                off += n;
                remaining -= n;
            }
            if (remaining != 0) {
                break;
            }

            udpLock.lock();
            try {
                DatagramSocket sock = datagramSocket;
                if (sock == null || sock.isClosed() || !sock.isConnected()) {
                    break;
                }
                packet.setData(buffer, 0, UDP_PACKET_BYTES);
                try {
                    sock.send(packet);
                } catch (IOException e) {
                    if (!closed.get()) {
                        log.debug("UDP audio send failed: {}", e.getMessage());
                    }
                }
            } finally {
                udpLock.unlock();
            }
        }
        recording.set(false);
    }

    private void receiveLoop() {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        byte[] buf = new byte[UDP_PACKET_BYTES];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (!closed.get()) {
            DatagramSocket sock;
            udpLock.lock();
            try {
                sock = datagramSocket;
            } finally {
                udpLock.unlock();
            }

            if (sock == null || sock.isClosed()) {
                parkBriefly();
                continue;
            }

            try {
                sock.receive(pkt);
                int len = pkt.getLength();
                if (len < UDP_IDENTITY_BYTES + PAYLOAD_SIZE) {
                    continue;
                }

                byte[] pktBuf = pkt.getData();
                int pktOff = pkt.getOffset();
                String speakerId = decodeUtf8Identity36Cached(pktBuf, pktOff);

                try {
                    ensurePlaybackOpen();
                } catch (LineUnavailableException e) {
                    log.warn("Playback unavailable: {}", e.getMessage());
                    continue;
                }

                SourceDataLine line;
                synchronized (playbackLock) {
                    line = playbackLine;
                }
                if (line != null) {
                    line.write(pktBuf, pktOff + UDP_IDENTITY_BYTES, PAYLOAD_SIZE);
                }

                UserSpeakingListener l = userSpeakingListener;
                if (l != null) {
                    l.onUserSpeaking(speakerId);
                }
            } catch (SocketException e) {
                if (closed.get()) {
                    break;
                }
                parkBriefly();
            } catch (IOException e) {
                if (!closed.get()) {
                    log.debug("UDP audio receive error: {}", e.getMessage());
                }
                parkBriefly();
            }
        }
        log.info("Audio receive loop exited");
    }

    private void ensurePlaybackOpen() throws LineUnavailableException {
        synchronized (playbackLock) {
            if (playbackLine != null && playbackLine.isOpen()) {
                return;
            }
            SourceDataLine line = AudioSystem.getSourceDataLine(AUDIO_FORMAT);
            line.open(AUDIO_FORMAT, LINE_BUFFER_BYTES);
            line.start();
            playbackLine = line;
        }
    }

    private static void parkBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] utf8Fixed36(String s) {
        byte[] raw = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UDP_IDENTITY_BYTES];
        int n = Math.min(UDP_IDENTITY_BYTES, raw.length);
        System.arraycopy(raw, 0, out, 0, n);
        return out;
    }

    private String decodeUtf8Identity36Cached(byte[] buf, int identityOffset) {
        if (rxSpeakerIdCacheValid
                && Arrays.equals(buf, identityOffset, identityOffset + UDP_IDENTITY_BYTES,
                rxIdentityMatch, 0, UDP_IDENTITY_BYTES)) {
            return rxSpeakerIdCached;
        }
        System.arraycopy(buf, identityOffset, rxIdentityMatch, 0, UDP_IDENTITY_BYTES);
        rxSpeakerIdCached = decodeUtf8Identity36(buf, identityOffset);
        rxSpeakerIdCacheValid = true;
        return rxSpeakerIdCached;
    }

    private static String decodeUtf8Identity36(byte[] buf, int identityOffset) {
        int end = identityOffset;
        int limit = identityOffset + UDP_IDENTITY_BYTES;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, identityOffset, end - identityOffset, StandardCharsets.UTF_8);
    }
}
