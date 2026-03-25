package com.distrisync.client;

/**
 * Legacy CLI entry point — delegates to the JavaFX {@link WhiteboardApp}.
 *
 * <p>Preferred launch method via Maven:
 * <pre>{@code
 *   mvn javafx:run
 *   # optional server override (host port):
 *   mvn javafx:run -Djavafx.args="192.168.1.10 9090"
 * }</pre>
 */
public class WhiteboardClient {

    public static void main(String[] args) {
        WhiteboardApp.main(args);
    }
}
