package com.distrisync.client;

import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main JavaFX entry point for the DistriSync collaborative whiteboard client.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>A resizable {@link Canvas} fills the center region; an
 *       {@link AnimationTimer} drives the 60-fps render loop entirely on the
 *       JavaFX Application Thread.</li>
 *   <li>All callbacks from {@link NetworkClient} arrive on background threads;
 *       every mutation of shared state is dispatched back via
 *       {@link Platform#runLater}.</li>
 *   <li>{@link UdpPointerTracker} owns its own send/receive threads and exposes
 *       a single {@link UdpPointerTracker#renderCursors} call that is safe to
 *       invoke from the Application Thread inside the render loop.</li>
 * </ul>
 *
 * <h2>Running</h2>
 * <pre>{@code
 *   mvn javafx:run
 *   # or, passing a custom server address:
 *   mvn javafx:run -Djavafx.args="localhost 9090"
 * }</pre>
 */
public class WhiteboardApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardApp.class);

    // ── defaults overridable via Application parameters ──────────────────────
    private static final String DEFAULT_HOST = "localhost";
    private static final int    DEFAULT_PORT = 9090;

    // ── theme palette (Catppuccin Mocha) ─────────────────────────────────────
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#313244";
    private static final String BG_OVERLAY = "#45475a";
    private static final String FG_TEXT    = "#cdd6f4";
    private static final String ACCENT     = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String RED        = "#f38ba8";

    // ── active drawing tool ───────────────────────────────────────────────────
    private enum Tool { LINE, CIRCLE }
    private volatile Tool activeTool = Tool.LINE;

    // ── canvas & graphics ────────────────────────────────────────────────────
    private Canvas         canvas;
    private GraphicsContext gc;

    // ── toolbar widgets ───────────────────────────────────────────────────────
    private ColorPicker colorPicker;
    private Slider      strokeSlider;
    private Label       statusLabel;

    // ── network ───────────────────────────────────────────────────────────────
    private NetworkClient     networkClient;
    private UdpPointerTracker udpTracker;

    // ── shape store – written from network thread via Platform.runLater,
    //    read from the FX application thread during rendering ─────────────────
    private final Map<UUID, Shape> shapes = new ConcurrentHashMap<>();

    // ── drag-preview state (FX Application Thread only) ──────────────────────
    private double  dragStartX, dragStartY;
    private double  dragCurrentX, dragCurrentY;
    private boolean isDragging;

    // =========================================================================
    // Application lifecycle
    // =========================================================================

    @Override
    public void start(Stage stage) {
        stage.setTitle("DistriSync – Collaborative Whiteboard");

        // ── canvas (fills available space via binding) ────────────────────────
        canvas = new Canvas();
        gc = canvas.getGraphicsContext2D();

        Pane canvasHost = new Pane(canvas);
        canvas.widthProperty().bind(canvasHost.widthProperty());
        canvas.heightProperty().bind(canvasHost.heightProperty());

        // ── toolbar ───────────────────────────────────────────────────────────
        ToolBar toolbar = buildToolbar();

        // ── root layout ───────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(canvasHost);
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        Scene scene = new Scene(root, 1280, 860);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        // ── wire mouse events ─────────────────────────────────────────────────
        wireMouseEvents();

        // ── start subsystems ──────────────────────────────────────────────────
        initNetworking();
        startRenderLoop();
    }

    @Override
    public void stop() {
        shutdown();
    }

    // =========================================================================
    // Toolbar construction
    // =========================================================================

    private ToolBar buildToolbar() {
        ToggleGroup toolGroup = new ToggleGroup();

        ToggleButton lineBtn   = styledToggle("Draw Line",   toolGroup, true);
        ToggleButton circleBtn = styledToggle("Draw Circle", toolGroup, false);

        lineBtn.setOnAction(e   -> activeTool = Tool.LINE);
        circleBtn.setOnAction(e -> activeTool = Tool.CIRCLE);

        colorPicker = new ColorPicker(Color.web(ACCENT));
        colorPicker.setStyle("-fx-pref-width: 80px; -fx-cursor: hand;");

        Label widthLabel = label("Width:");
        strokeSlider = new Slider(1, 20, 2);
        strokeSlider.setShowTickLabels(true);
        strokeSlider.setPrefWidth(110);
        strokeSlider.setStyle("-fx-pref-height: 28px;");

        statusLabel = label("⬤ Offline");
        statusLabel.setStyle("-fx-text-fill: " + RED + "; -fx-font-size: 11px;");

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar tb = new ToolBar(
                lineBtn, circleBtn, new Separator(),
                label("Color:"), colorPicker, new Separator(),
                widthLabel, strokeSlider, spacer,
                statusLabel
        );
        tb.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-padding: 6 10;");
        return tb;
    }

    private ToggleButton styledToggle(String text, ToggleGroup group, boolean selected) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setSelected(selected);
        applyToggleStyle(btn, selected);
        btn.selectedProperty().addListener((obs, was, now) -> applyToggleStyle(btn, now));
        return btn;
    }

    private void applyToggleStyle(ToggleButton btn, boolean active) {
        if (active) {
            btn.setStyle(
                "-fx-background-color: " + ACCENT + "; -fx-text-fill: " + BG_BASE + ";" +
                "-fx-background-radius: 5; -fx-padding: 5 14; -fx-cursor: hand; -fx-font-weight: bold;"
            );
        } else {
            btn.setStyle(
                "-fx-background-color: " + BG_OVERLAY + "; -fx-text-fill: " + FG_TEXT + ";" +
                "-fx-background-radius: 5; -fx-padding: 5 14; -fx-cursor: hand;"
            );
        }
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + FG_TEXT + "; -fx-font-size: 12px;");
        return l;
    }

    // =========================================================================
    // Mouse events
    // =========================================================================

    private void wireMouseEvents() {
        canvas.setOnMousePressed(e -> {
            dragStartX   = e.getX();
            dragStartY   = e.getY();
            dragCurrentX = e.getX();
            dragCurrentY = e.getY();
            isDragging   = true;
        });

        canvas.setOnMouseDragged(e -> {
            dragCurrentX = e.getX();
            dragCurrentY = e.getY();
            notifyUdpMove(e.getX(), e.getY());
        });

        canvas.setOnMouseReleased(e -> {
            if (!isDragging) return;
            isDragging = false;

            double dx = e.getX() - dragStartX;
            double dy = e.getY() - dragStartY;
            // Ignore accidental single-pixel clicks
            if (Math.sqrt(dx * dx + dy * dy) < 2.0) return;

            commitShape(e.getX(), e.getY(), dx, dy);
        });

        canvas.setOnMouseMoved(e -> notifyUdpMove(e.getX(), e.getY()));
    }

    /**
     * Creates a {@link Shape} from the completed drag, stores it locally, and
     * immediately pushes it onto the {@link NetworkClient} write queue so it
     * is broadcast to peers without waiting for a server round-trip.
     */
    private void commitShape(double endX, double endY, double dx, double dy) {
        String color       = toHexString(colorPicker.getValue());
        double strokeWidth = strokeSlider.getValue();

        Shape shape = switch (activeTool) {
            case LINE   -> Line.create(color, dragStartX, dragStartY, endX, endY, strokeWidth);
            case CIRCLE -> {
                double radius = Math.sqrt(dx * dx + dy * dy);
                yield Circle.create(color, dragStartX, dragStartY, Math.max(1.0, radius));
            }
        };

        shapes.put(shape.objectId(), shape);

        if (networkClient != null) {
            try {
                networkClient.sendMutation(shape);
            } catch (Exception ex) {
                log.warn("sendMutation failed: {}", ex.getMessage());
            }
        }
    }

    private void notifyUdpMove(double x, double y) {
        if (udpTracker != null) udpTracker.onMouseMoved(x, y);
    }

    // =========================================================================
    // Networking
    // =========================================================================

    private void initNetworking() {
        Parameters params = getParameters();
        List<String> raw  = params.getRaw();
        String host = raw.size() > 0 ? raw.get(0) : DEFAULT_HOST;
        int    port = raw.size() > 1 ? parseInt(raw.get(1), DEFAULT_PORT) : DEFAULT_PORT;

        networkClient = new NetworkClient(host, port);

        // CanvasUpdateListener callbacks arrive on distrisync-read; wrap in runLater
        networkClient.addListener(new CanvasUpdateListener() {

            @Override
            public void onSnapshotReceived(List<Shape> incoming) {
                Platform.runLater(() -> {
                    shapes.clear();
                    incoming.forEach(s -> shapes.put(s.objectId(), s));
                    log.info("Snapshot applied — {} shape(s) on canvas", shapes.size());
                });
            }

            @Override
            public void onMutationReceived(Shape shape) {
                // Ignore echoes of our own locally-committed shapes (same objectId already present)
                Platform.runLater(() -> shapes.put(shape.objectId(), shape));
            }
        });

        // Connect asynchronously so the UI is never blocked
        Thread connectThread = new Thread(() -> {
            try {
                networkClient.connect();
                Platform.runLater(() -> setStatus("⬤ Connected to " + host + ":" + port, GREEN));
            } catch (IOException e) {
                log.warn("Could not reach server at {}:{} — offline mode active", host, port);
                Platform.runLater(() -> setStatus("⬤ Offline", RED));
            }
        }, "distrisync-connect");
        connectThread.setDaemon(true);
        connectThread.start();

        // UDP pointer tracker — runs its own send/receive threads
        udpTracker = new UdpPointerTracker(canvas);
        udpTracker.start();
    }

    private void setStatus(String text, String colorHex) {
        statusLabel.setText(text);
        statusLabel.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 11px;");
    }

    // =========================================================================
    // Render loop
    // =========================================================================

    private void startRenderLoop() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                render();
            }
        }.start();
    }

    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Background
        gc.setFill(Color.web("#ffffff"));
        gc.fillRect(0, 0, w, h);

        // Draw committed shapes
        for (Shape shape : shapes.values()) {
            drawShape(shape, 1.0);
        }

        // Draw in-progress drag preview
        if (isDragging) {
            drawPreview();
        }

        // Draw remote cursors (safe — called from FX Application Thread)
        if (udpTracker != null) {
            udpTracker.renderCursors(gc);
        }
    }

    // =========================================================================
    // Shape rendering
    // =========================================================================

    private void drawShape(Shape shape, double opacity) {
        gc.save();
        gc.setGlobalAlpha(opacity);

        switch (shape) {
            case Line l -> {
                gc.setStroke(parseColor(l.color()));
                gc.setLineWidth(l.strokeWidth());
                gc.setLineDashes(0);
                gc.strokeLine(l.x1(), l.y1(), l.x2(), l.y2());
            }
            case Circle c -> {
                Color color = parseColor(c.color());
                double d    = c.radius() * 2;
                double ox   = c.x() - c.radius();
                double oy   = c.y() - c.radius();
                if (c.filled()) {
                    gc.setFill(color);
                    gc.fillOval(ox, oy, d, d);
                } else {
                    gc.setStroke(color);
                    gc.setLineWidth(c.strokeWidth());
                    gc.setLineDashes(0);
                    gc.strokeOval(ox, oy, d, d);
                }
            }
            case TextNode t -> {
                Font font = Font.font(
                    t.fontFamily(),
                    t.bold()   ? FontWeight.BOLD   : FontWeight.NORMAL,
                    t.italic() ? FontPosture.ITALIC : FontPosture.REGULAR,
                    t.fontSize()
                );
                gc.setFont(font);
                gc.setFill(parseColor(t.color()));
                gc.fillText(t.content(), t.x(), t.y());
            }
        }

        gc.restore();
    }

    /**
     * Renders a dashed ghost of the shape currently being drawn during a drag.
     * Drawn at reduced opacity to distinguish it from committed shapes.
     */
    private void drawPreview() {
        double dx     = dragCurrentX - dragStartX;
        double dy     = dragCurrentY - dragStartY;
        Color  stroke = colorPicker.getValue();
        double width  = strokeSlider.getValue();

        gc.save();
        gc.setGlobalAlpha(0.55);
        gc.setStroke(stroke);
        gc.setLineWidth(width);
        gc.setLineDashes(7, 5);

        switch (activeTool) {
            case LINE -> gc.strokeLine(dragStartX, dragStartY, dragCurrentX, dragCurrentY);
            case CIRCLE -> {
                double r  = Math.sqrt(dx * dx + dy * dy);
                double ox = dragStartX - r;
                double oy = dragStartY - r;
                gc.strokeOval(ox, oy, r * 2, r * 2);
            }
        }

        gc.restore();
    }

    // =========================================================================
    // Shutdown
    // =========================================================================

    private void shutdown() {
        if (udpTracker    != null) udpTracker.stop();
        if (networkClient != null) networkClient.close();
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private Color parseColor(String css) {
        try {
            return Color.web(css);
        } catch (IllegalArgumentException e) {
            return Color.BLACK;
        }
    }

    private static String toHexString(Color c) {
        return String.format("#%02X%02X%02X",
            (int) Math.round(c.getRed()   * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue()  * 255)
        );
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        launch(args);
    }
}
