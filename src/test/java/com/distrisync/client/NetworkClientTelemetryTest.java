package com.distrisync.client;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageType;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Telemetry / PONG handling: {@link NetworkClient#pingProperty()} must reflect
 * {@code System.currentTimeMillis() - originTimestamp} when a PONG is processed.
 */
class NetworkClientTelemetryTest {

    @BeforeAll
    static void initJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException ignored) {
            // Toolkit already started by another test class in the same JVM.
        }
    }

    @Test
    void pongReceipt_setsPingPropertyToElapsedSinceOriginTimestamp() {
        NetworkClient client = new NetworkClient("127.0.0.1", 1, "author", "client-id");
        // Origin is fixed in the payload; the client computes RTT when the FX queue runs applyPingRtt.
        final long simulatedAgeMs = 100L;
        long wallBeforeIngest = System.currentTimeMillis();
        long mockedOrigin = wallBeforeIngest - simulatedAgeMs;
        String payload = "{\"t\":" + mockedOrigin + "}";
        Message pong = new Message(MessageType.PONG, payload);

        client.ingestPongForTelemetryTest(pong);

        await().atMost(3, TimeUnit.SECONDS).until(() -> client.pingProperty().get() >= 0L);

        long wallAfterApply = System.currentTimeMillis();
        long measured = client.pingProperty().get();
        long minExpected = wallBeforeIngest - mockedOrigin - 5L;
        long maxExpected = wallAfterApply - mockedOrigin + 5L;
        assertThat(measured)
                .as("pingProperty = wallAtApply - origin; wallAtApply is between ingest and read-back")
                .isBetween(minExpected, maxExpected);
    }
}
