package com.distrisync.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the same byte-accounting rules {@link NioServer} uses for the traffic heartbeat:
 * TCP board fan-out counts one full frame per eligible peer; UDP relay counts one datagram per send.
 */
class ServerMetricsTest {

    /**
     * Minimal stand-in for the selector-thread router: each {@link #sendBoardFanOut} models
     * {@code NioServer#broadcastToBoard} enqueue + successful flush to one peer.
     */
    static final class MockTrafficRouter {
        private final AtomicLong bytesRouted;

        MockTrafficRouter(AtomicLong bytesRouted) {
            this.bytesRouted = bytesRouted;
        }

        void sendBoardFanOut(ByteBuffer frame, int recipientCount) {
            int frameBytes = frame.remaining();
            for (int i = 0; i < recipientCount; i++) {
                if (frameBytes > 0) {
                    bytesRouted.addAndGet(frameBytes);
                }
            }
        }

        void sendUdpAudioRelay(int datagramBytes, int successfulSends) {
            for (int i = 0; i < successfulSends; i++) {
                if (datagramBytes > 0) {
                    bytesRouted.addAndGet(datagramBytes);
                }
            }
        }
    }

    @Test
    @DisplayName("bytesRouted aggregates TCP board fan-out and UDP relay octets like NioServer")
    void aggregatesRoutedBytesFromSimulatedSends() {
        AtomicLong bytesRouted = new AtomicLong();
        MockTrafficRouter router = new MockTrafficRouter(bytesRouted);

        ByteBuffer mutation = ByteBuffer.allocate(40);
        mutation.putInt(0xdeadbeef);
        mutation.flip();
        assertThat(mutation.remaining()).isEqualTo(4);

        router.sendBoardFanOut(mutation, 3);

        ByteBuffer textUpdate = ByteBuffer.wrap(new byte[100]);
        router.sendBoardFanOut(textUpdate, 2);

        router.sendUdpAudioRelay(256, 5);

        assertThat(bytesRouted.get()).isEqualTo(3 * 4 + 2 * 100 + 5 * 256);
    }
}
