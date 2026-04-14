package com.distrisync.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format and sizing invariants for {@link AudioEngine} (no audio hardware,
 * sockets, or threads).
 */
@DisplayName("AudioEngine")
class AudioEngineTest {

    @Test
    @DisplayName("10 ms frame at 8 kHz, 16-bit mono is exactly 160 bytes")
    void testAudioFrameCalculations() {
        AudioFormat format = AudioEngine.AUDIO_FORMAT;

        assertThat(format.getSampleRate()).isEqualTo(8000.0f);
        assertThat(format.getSampleSizeInBits()).isEqualTo(16);
        assertThat(format.getChannels()).isEqualTo(1);

        int sampleRate = Math.round(format.getSampleRate());
        int bytesPerSample = format.getSampleSizeInBits() / 8;
        int channels = format.getChannels();
        assertThat(format.getFrameSize()).isEqualTo(bytesPerSample * channels);

        final int frameDurationMs = 10;
        // bytes = sampleRate * (ms/1000) * bytesPerSample * channels  (exact integers here)
        long bytesPer10ms =
                (long) sampleRate * frameDurationMs / 1000L * bytesPerSample * channels;

        assertThat(bytesPer10ms).isEqualTo(160L);
        assertThat(AudioEngine.PAYLOAD_SIZE).isEqualTo((int) bytesPer10ms);
    }

    /**
     * Guards micro-framing and UDP capture/receive buffer sizing: {@link AudioEngine} allocates
     * {@code new byte[UDP_PACKET_BYTES]} as 36-byte identity + {@link AudioEngine#PAYLOAD_SIZE} PCM.
     */
    @Test
    @DisplayName("Micro-frame latency math: 10 ms PCM = 160 B; wire datagram allocation = 196 B")
    void testMicroFrameLatencyMath() {
        AudioFormat format = AudioEngine.AUDIO_FORMAT;

        assertThat(format.getSampleRate()).isEqualTo(8000.0f);
        assertThat(format.getSampleSizeInBits()).isEqualTo(16);
        assertThat(format.getChannels()).isEqualTo(1);

        final double microFrameSeconds = 0.01d;
        long samplesInMicroFrame = Math.round(format.getSampleRate() * microFrameSeconds);
        long pcmBytesFor10ms = samplesInMicroFrame
                * (format.getSampleSizeInBits() / 8L)
                * format.getChannels();

        assertThat(pcmBytesFor10ms)
                .as("PCM bytes for exactly %.2f s at sample rate %s Hz, %d-bit, %d channel(s)",
                        microFrameSeconds, format.getSampleRate(), format.getSampleSizeInBits(), format.getChannels())
                .isEqualTo(160L);
        assertThat(AudioEngine.PAYLOAD_SIZE)
                .as("PAYLOAD_SIZE must equal the 10 ms micro-frame on the wire")
                .isEqualTo((int) pcmBytesFor10ms);

        // Must match AudioEngine UDP_IDENTITY_BYTES — UUID UTF-8 admission token on the wire.
        final int uuidTokenWireBytes = 36;
        int totalUdpPacketBytes = uuidTokenWireBytes + AudioEngine.PAYLOAD_SIZE;
        assertThat(totalUdpPacketBytes)
                .as("UDP datagram buffer: %d-byte identity + %d-byte PCM payload",
                        uuidTokenWireBytes, AudioEngine.PAYLOAD_SIZE)
                .isEqualTo(196);
    }
}
