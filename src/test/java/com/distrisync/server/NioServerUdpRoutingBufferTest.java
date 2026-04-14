package com.distrisync.server;

import com.distrisync.client.AudioEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the direct-buffer layout and cursor rules used by
 * {@link NioServer} UDP relay ({@code handleUdpRead}): 36-byte identity prefix plus
 * {@link AudioEngine#PAYLOAD_SIZE} bytes of PCM.
 */
class NioServerUdpRoutingBufferTest {

    private static final int UDP_IDENTITY_BYTES = 36;

    /**
     * Mirrors {@link NioServer}: after {@code receive}, {@code flip()}, and consuming the token with
     * {@code get(byte[])}, the payload region must still be {@link AudioEngine#PAYLOAD_SIZE} bytes —
     * otherwise a header swap would silently truncate audio.
     */
    @Test
    @DisplayName("Direct buffer: after token read, 160 bytes remain (196-byte wire frame)")
    void testDirectBufferLimits() {
        ByteBuffer udpBuffer = ByteBuffer.allocateDirect(1024);

        assertThat(udpBuffer.isDirect())
                .as("Production path uses allocateDirect for OS-facing I/O")
                .isTrue();
        assertThat(udpBuffer.capacity())
                .isGreaterThanOrEqualTo(UDP_IDENTITY_BYTES + AudioEngine.PAYLOAD_SIZE);

        String token = UUID.randomUUID().toString();
        byte[] tokenUtf8 = token.getBytes(StandardCharsets.UTF_8);
        assertThat(tokenUtf8)
                .as("Admission tokens are UUID strings (36 UTF-8 bytes)")
                .hasSize(UDP_IDENTITY_BYTES);

        udpBuffer.put(tokenUtf8);
        byte[] pcm10ms = new byte[AudioEngine.PAYLOAD_SIZE];
        for (int i = 0; i < pcm10ms.length; i++) {
            pcm10ms[i] = (byte) (i & 0xFF);
        }
        udpBuffer.put(pcm10ms);

        assertThat(udpBuffer.position())
                .isEqualTo(UDP_IDENTITY_BYTES + AudioEngine.PAYLOAD_SIZE);

        udpBuffer.flip();
        assertThat(udpBuffer.remaining())
                .isEqualTo(UDP_IDENTITY_BYTES + AudioEngine.PAYLOAD_SIZE);

        byte[] scratch = new byte[UDP_IDENTITY_BYTES];
        udpBuffer.get(scratch);
        assertThat(scratch).isEqualTo(tokenUtf8);

        assertThat(udpBuffer.remaining())
                .as("Token consumption must leave full 10 ms PCM slice (160 bytes) for relay / header overwrite")
                .isEqualTo(AudioEngine.PAYLOAD_SIZE);

        byte[] payloadReadBack = new byte[AudioEngine.PAYLOAD_SIZE];
        udpBuffer.get(payloadReadBack);
        assertThat(payloadReadBack).isEqualTo(pcm10ms);
    }
}
