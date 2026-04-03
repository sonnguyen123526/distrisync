package com.distrisync.client;

import com.distrisync.model.Circle;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main JavaFX entry point for the DistriSync collaborative whiteboard client.
 *
 * <h2>Canvas architecture — three-layer StackPane</h2>
 * <ul>
 *   <li><b>Layer 1 — {@code baseCanvas}</b>: re-rendered every frame by an
 *       {@link AnimationTimer}; draws the white background and all committed
 *       shapes sorted by Lamport timestamp.</li>
 *   <li><b>Layer 2 — {@code transientCanvas}</b>: transparent by default;
 *       updated directly from mouse-drag events to show the rubber-band preview
 *       (Line / Circle) or the in-progress freehand / eraser stroke.  Cleared
 *       completely on {@code MOUSE_RELEASED} before the shape is committed.</li>
 *   <li><b>Layer 3 — {@code cursorPane}</b>: a transparent {@link Pane} that
 *       hosts JavaFX {@code Group} nodes for each remote cursor.
 *       {@link UdpPointerTracker} manages these nodes exclusively via
 *       {@link Platform#runLater}.</li>
 * </ul>
 *
 * <h2>Thread model</h2>
 * All {@link NetworkClient} callbacks arrive on background threads and are
 * marshalled back to the FX Application Thread via {@link Platform#runLater}
 * before touching any shared state.  The {@link UdpPointerTracker} owns its
 * own send/receive threads; UI mutations happen exclusively inside
 * {@code Platform.runLater}.
 */
public class WhiteboardApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardApp.class);

    // ── server defaults ───────────────────────────────────────────────────────
    private static final String DEFAULT_HOST = "localhost";
    private static final int    DEFAULT_PORT = 9090;

    // ── Catppuccin Mocha / dark palette ───────────────────────────────────────
    private static final String BG_BASE      = "#1e1e2e";
    private static final String BG_OVERLAY   = "#45475a";
    private static final String FG_TEXT      = "#cdd6f4";
    private static final String FG_MUTED     = "#7f849c";
    private static final String ACCENT       = "#89b4fa";
    private static final String GREEN        = "#a6e3a1";
    private static final String RED          = "#f38ba8";
    private static final String TOOLBAR_BG   = "#2C2F33";

    // ── toolbar geometry ──────────────────────────────────────────────────────
    private static final double TOOLBAR_WIDTH = 200.0;

    // ── drawing constants ─────────────────────────────────────────────────────
    private static final double MIN_DRAG_DIST     = 2.0;
    private static final double MIN_FREEHAND_STEP = 4.0;
    private static final double ERASER_BASE_WIDTH = 14.0;

    // ── active drawing tool ───────────────────────────────────────────────────
    private enum Tool { LINE, CIRCLE, FREEHAND, ERASER, TEXT }
    private volatile Tool activeTool = Tool.LINE;

    // ── canvas layers ─────────────────────────────────────────────────────────
    private Canvas          baseCanvas;             // Layer 1: committed shapes
    private Canvas          remoteTransientCanvas;  // Layer 2: remote peers' in-progress shapes
    private Canvas          transientCanvas;        // Layer 3: local rubber-band / freehand preview
    private GraphicsContext baseGc;
    private GraphicsContext remoteTransientGc;
    private GraphicsContext transientGc;
    private Pane            cursorPane;             // Layer 4: remote-cursor JavaFX nodes
    private Pane            controlPane;            // Layer 5: floating text-input controls

    // ── Eraser cursor overlay — square that tracks the mouse when Eraser is active ─
    private Rectangle       eraserCursor;

    // ── toolbar controls ──────────────────────────────────────────────────────
    private ColorPicker colorPicker;
    private Slider      strokeSlider;
    private Label       statusLabel;

    // ── shape-ownership tooltip (shown on hover over committed shapes) ─────────
    private Tooltip ownerTooltip;

    // ── network subsystems ────────────────────────────────────────────────────
    private NetworkClient     networkClient;
    private UdpPointerTracker udpTracker;

    // ── user identity (captured at startup via name dialog) ───────────────────
    private String authorName = "Anonymous";
    private String clientId   = UUID.randomUUID().toString();

    /**
     * LIFO history of shape IDs committed by this local user during the current
     * session.  Used to implement single-level undo via {@code UNDO_REQUEST}.
     * Accessed exclusively on the FX Application Thread.
     */
    private final Deque<UUID> undoHistory = new ArrayDeque<>();

    // ── committed shape store (FX thread only; written via Platform.runLater) ─
    private final Map<UUID, Shape> shapes = new ConcurrentHashMap<>();

    // ── drag / freehand state (FX Application Thread only) ───────────────────
    private double         dragStartX, dragStartY;
    private double         dragCurrentX, dragCurrentY;
    private boolean        isDragging;
    private final List<double[]> freehandPoints = new ArrayList<>();
    private double         lastFreehandX, lastFreehandY;

    // ── live-drawing streaming state (FX Application Thread only) ────────────
    private UUID activeShapeId;
    private long lastSendTime = 0;

    // ── remote peers' in-progress shapes (written on FX thread via runLater) ─
    private final Map<UUID, TransientShapeEntry> transientShapes = new ConcurrentHashMap<>();

    // ── ghost text overlays for remote live-typing (FX Application Thread only) ─
    private final Map<UUID, VBox> ghostTextNodes = new ConcurrentHashMap<>();

    // ── UUID of the local TextField currently being composed (FX thread only) ─
    private UUID activeTextId;

    // =========================================================================
    // Application lifecycle
    // =========================================================================

    @Override
    public void start(Stage stage) {
        // Prompt for the user's display name before anything else is shown.
        // This runs synchronously on the FX thread so networking (which embeds
        // the name in the HANDSHAKE) starts only after the dialog is dismissed.
        TextInputDialog nameDialog = new TextInputDialog("Anonymous");
        nameDialog.setTitle("DistriSync – Welcome");
        nameDialog.setHeaderText("Collaborative Whiteboard");
        nameDialog.setContentText("Your display name:");
        nameDialog.getDialogPane().setStyle("-fx-background-color: #1e1e2e; -fx-font-size: 13px;");
        Optional<String> nameResult = nameDialog.showAndWait();
        String rawName = nameResult.orElse("Anonymous").strip();
        authorName = rawName.isBlank() ? "Anonymous" : rawName;
        clientId   = UUID.randomUUID().toString();

        stage.setTitle("DistriSync – " + authorName);

        // ── Layer 1: base canvas ──────────────────────────────────────────────
        baseCanvas = new Canvas();
        baseGc     = baseCanvas.getGraphicsContext2D();

        // ── Layer 2: remote-transient canvas (peers' in-progress shapes) ──────
        remoteTransientCanvas = new Canvas();
        remoteTransientGc     = remoteTransientCanvas.getGraphicsContext2D();

        // ── Layer 3: local transient canvas (rubber-band / freehand preview) ──
        transientCanvas = new Canvas();
        transientGc     = transientCanvas.getGraphicsContext2D();

        // ── Layer 4: cursor pane (transparent, mouse-transparent) ─────────────
        cursorPane = new Pane();
        cursorPane.setMouseTransparent(true);

        // ── Layer 5: control pane for floating text-input widgets ─────────────
        // Starts mouse-transparent; becomes interactive only while a TextField
        // is actively placed on it, then returns to transparent on commit/cancel.
        controlPane = new Pane();
        controlPane.setMouseTransparent(true);
        controlPane.setStyle("-fx-background-color: transparent;");

        // ── Stack all five layers ─────────────────────────────────────────────
        StackPane canvasStack = new StackPane(
                baseCanvas, remoteTransientCanvas, transientCanvas, cursorPane, controlPane);

        // Bind canvas dimensions to the StackPane so they resize with the window
        baseCanvas.widthProperty().bind(canvasStack.widthProperty());
        baseCanvas.heightProperty().bind(canvasStack.heightProperty());
        remoteTransientCanvas.widthProperty().bind(canvasStack.widthProperty());
        remoteTransientCanvas.heightProperty().bind(canvasStack.heightProperty());
        transientCanvas.widthProperty().bind(canvasStack.widthProperty());
        transientCanvas.heightProperty().bind(canvasStack.heightProperty());
        cursorPane.prefWidthProperty().bind(canvasStack.widthProperty());
        cursorPane.prefHeightProperty().bind(canvasStack.heightProperty());
        controlPane.prefWidthProperty().bind(canvasStack.widthProperty());
        controlPane.prefHeightProperty().bind(canvasStack.heightProperty());

        // ── Root layout ───────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setLeft(buildToolbar());
        root.setCenter(canvasStack);
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        // ── Ownership tooltip — shown when hovering over a committed shape ────
        ownerTooltip = new Tooltip();
        ownerTooltip.setShowDelay(Duration.millis(350));
        ownerTooltip.setHideDelay(Duration.ZERO);
        ownerTooltip.setShowDuration(Duration.seconds(6));
        ownerTooltip.setStyle(
            "-fx-background-color: #313244; -fx-text-fill: #cdd6f4;" +
            "-fx-font-size: 12px; -fx-padding: 5 9; -fx-background-radius: 6;");

        Scene scene = new Scene(root, 1280, 860);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        // ── Ctrl+Z keyboard shortcut for undo ─────────────────────────────────
        scene.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.Z) {
                undoLastShape();
            }
        });

        stage.setScene(scene);
        stage.show();

        // controlPane must sit above cursorPane; toFront() reaffirms StackPane z-order.
        controlPane.toFront();

        // Eraser cursor square — must be created after strokeSlider and cursorPane exist
        setupEraserCursor();

        // Wire all mouse events to the StackPane (always hit-testable)
        wireMouseEvents(canvasStack);

        initNetworking();
        startRenderLoop();
    }

    @Override
    public void stop() {
        shutdown();
        // Force a clean JVM exit to bypass the JavaFX Direct3D native teardown that
        // produces a STATUS_STACK_BUFFER_OVERRUN (0xC0000409) crash on Windows when
        // the D3D pipeline cleans up its native threads after the FX toolkit exits.
        System.exit(0);
    }

    // =========================================================================
    // Toolbar construction
    // =========================================================================

    private VBox buildToolbar() {
        // Master sidebar — spacing of 25 lets the sections breathe
        VBox box = new VBox(25);
        box.setPrefWidth(TOOLBAR_WIDTH);
        box.setMinWidth(TOOLBAR_WIDTH);
        box.setMaxWidth(TOOLBAR_WIDTH);
        box.setPadding(new Insets(20));
        box.getStyleClass().add("sidebar");

        // App title
        Label title = new Label("DistriSync");
        title.setStyle(
            "-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold; -fx-padding: 0 0 4 0;");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        // Tool toggle group — exactly one active at a time
        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton lineBtn   = toolToggle("✏  Line",   toolGroup, true);
        ToggleButton circleBtn = toolToggle("◯  Circle", toolGroup, false);
        ToggleButton penBtn    = toolToggle("🖊  Pen",    toolGroup, false);
        ToggleButton eraserBtn = toolToggle("◻  Eraser", toolGroup, false);
        ToggleButton textBtn   = toolToggle("T  Text",   toolGroup, false);

        lineBtn.setOnAction(e   -> { activeTool = Tool.LINE;     dismissActiveTextField(); });
        circleBtn.setOnAction(e -> { activeTool = Tool.CIRCLE;   dismissActiveTextField(); });
        penBtn.setOnAction(e    -> { activeTool = Tool.FREEHAND; dismissActiveTextField(); });
        eraserBtn.setOnAction(e -> { activeTool = Tool.ERASER;   dismissActiveTextField(); });
        textBtn.setOnAction(e   -> { activeTool = Tool.TEXT;     dismissActiveTextField(); });

        // ── Cursor affordance: update mouse cursor when the active tool changes ─
        toolGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) return;
            if (newToggle == eraserBtn) {
                // Eraser: hide the OS cursor; the square eraserCursor node takes its place
                if (cursorPane      != null) cursorPane.setCursor(Cursor.NONE);
                if (transientCanvas != null) transientCanvas.setCursor(Cursor.NONE);
            } else {
                // Any other tool: hide the eraser square and restore the OS cursor
                if (eraserCursor    != null) eraserCursor.setVisible(false);
                Cursor cursor = (newToggle == textBtn) ? Cursor.TEXT : Cursor.CROSSHAIR;
                if (cursorPane      != null) cursorPane.setCursor(cursor);
                if (transientCanvas != null) transientCanvas.setCursor(cursor);
            }
        });
        // Apply the initial cursor for the default tool (LINE → CROSSHAIR)
        if (cursorPane      != null) cursorPane.setCursor(Cursor.CROSSHAIR);
        if (transientCanvas != null) transientCanvas.setCursor(Cursor.CROSSHAIR);

        // ── TOOLS section ────────────────────────────────────────────────────
        VBox toolsSection = new VBox(10,
            sectionLabel("TOOLS"),
            lineBtn, circleBtn, penBtn, eraserBtn, textBtn
        );

        // ── COLOR section ────────────────────────────────────────────────────
        colorPicker = new ColorPicker(Color.web(ACCENT));
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        colorPicker.setStyle("-fx-cursor: hand;");

        VBox colorSection = new VBox(10,
            sectionLabel("COLOR"),
            colorPicker
        );

        // ── STROKE WIDTH section ──────────────────────────────────────────────
        strokeSlider = new Slider(1, 20, 2);
        strokeSlider.setShowTickLabels(true);
        strokeSlider.setMajorTickUnit(5);
        strokeSlider.setMaxWidth(Double.MAX_VALUE);

        VBox strokeSection = new VBox(10,
            sectionLabel("STROKE WIDTH"),
            strokeSlider
        );

        // ── Action buttons ────────────────────────────────────────────────────
        Button undoBtn = new Button("⤺  Undo Last");
        undoBtn.setMaxWidth(Double.MAX_VALUE);
        undoBtn.getStyleClass().add("tool-button");
        undoBtn.setOnAction(e -> undoLastShape());

        Button clearBtn = new Button("Clear Board");
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.getStyleClass().add("danger-button");
        clearBtn.setOnAction(e -> clearBoard());

        VBox actionsSection = new VBox(10, undoBtn, clearBtn);

        // ── Status label — Region spacer pushes it to the absolute bottom ─────
        statusLabel = new Label("⬤ Offline");
        statusLabel.setStyle("-fx-text-fill: " + RED + "; -fx-font-size: 12px;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setAlignment(Pos.CENTER);
        VBox.setMargin(statusLabel, new Insets(0, 0, 4, 0));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        box.getChildren().addAll(
            title,
            toolsSection,
            colorSection,
            strokeSection,
            actionsSection,
            spacer,
            statusLabel
        );

        return box;
    }

    private ToggleButton toolToggle(String text, ToggleGroup group, boolean selected) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setSelected(selected);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.getStyleClass().add("tool-button");
        return btn;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-header");
        return l;
    }

    // =========================================================================
    // Mouse events
    // =========================================================================

    /**
     * Wires all drawing interactions to the {@link StackPane} so events are
     * captured regardless of which canvas layer is topmost.
     *
     * <p>Rubber-band shapes (LINE, CIRCLE) draw their preview to
     * {@code transientCanvas} on every drag update and clear it on release.
     * Freehand / eraser paths accumulate points incrementally on
     * {@code transientCanvas}; on release the path is committed as a series
     * of {@link Line} mutations.
     */
    private void wireMouseEvents(StackPane target) {
        target.setOnMousePressed(e -> {
            ownerTooltip.hide();

            // Text tool: place a floating TextField at the click point and bail out
            // before the drag-drawing machinery initialises.
            if (activeTool == Tool.TEXT) {
                placeTextField(e.getX(), e.getY());
                return;
            }

            dragStartX   = e.getX();
            dragStartY   = e.getY();
            dragCurrentX = e.getX();
            dragCurrentY = e.getY();
            isDragging   = true;

            if (activeTool == Tool.FREEHAND || activeTool == Tool.ERASER) {
                freehandPoints.clear();
                freehandPoints.add(new double[]{e.getX(), e.getY()});
                lastFreehandX = e.getX();
                lastFreehandY = e.getY();
            }

            // Assign a fresh shape identity for this gesture and broadcast SHAPE_START.
            activeShapeId = UUID.randomUUID();
            if (networkClient != null) {
                String toolName    = activeTool.name();
                String color       = toHexString(colorPicker.getValue());
                double strokeWidth = strokeSlider.getValue();
                if (activeTool == Tool.ERASER) {
                    color       = "#FFFFFF";
                    strokeWidth = strokeSlider.getValue() * 3.0;
                }
                try {
                    networkClient.sendShapeStart(activeShapeId, toolName, color, strokeWidth,
                                                 e.getX(), e.getY());
                } catch (Exception ex) {
                    log.warn("sendShapeStart failed: {}", ex.getMessage());
                }
            }
        });

        target.setOnMouseDragged(e -> {
            dragCurrentX = e.getX();
            dragCurrentY = e.getY();

            switch (activeTool) {
                case LINE, CIRCLE -> {
                    // Clear and redraw the rubber-band preview on every drag tick
                    transientGc.clearRect(0, 0,
                            transientCanvas.getWidth(), transientCanvas.getHeight());
                    drawRubberBandPreview();
                }
                case FREEHAND, ERASER -> {
                    double dx = e.getX() - lastFreehandX;
                    double dy = e.getY() - lastFreehandY;
                    if (Math.sqrt(dx * dx + dy * dy) >= MIN_FREEHAND_STEP) {
                        drawFreehandSegment(lastFreehandX, lastFreehandY,
                                            e.getX(),       e.getY());
                        freehandPoints.add(new double[]{e.getX(), e.getY()});
                        lastFreehandX = e.getX();
                        lastFreehandY = e.getY();
                    }
                }
            }

            // Throttled SHAPE_UPDATE: send at most once every 40 ms to keep bandwidth low.
            long now = System.currentTimeMillis();
            if (networkClient != null && activeShapeId != null && now - lastSendTime > 40) {
                lastSendTime = now;
                try {
                    networkClient.sendShapeUpdate(activeShapeId, e.getX(), e.getY());
                } catch (Exception ex) {
                    log.warn("sendShapeUpdate failed: {}", ex.getMessage());
                }
            }

            notifyUdpMove(e.getX(), e.getY());
            updateEraserCursorPosition(e.getX(), e.getY());
        });

        target.setOnMouseReleased(e -> {
            if (!isDragging) return;
            isDragging = false;

            // Always clear the local transient layer before committing.
            transientGc.clearRect(0, 0,
                    transientCanvas.getWidth(), transientCanvas.getHeight());

            // Tell remote peers to flush their transient preview for this gesture.
            if (networkClient != null && activeShapeId != null) {
                try {
                    networkClient.sendShapeCommit(activeShapeId);
                } catch (Exception ex) {
                    log.warn("sendShapeCommit failed: {}", ex.getMessage());
                }
            }

            commitShape(e.getX(), e.getY());
        });

        target.setOnMouseMoved(e -> {
            notifyUdpMove(e.getX(), e.getY());
            updateEraserCursorPosition(e.getX(), e.getY());
            // Hover-ownership: find topmost shape under the cursor and show
            // a tooltip attributing it to its author.
            if (!isDragging) {
                Shape hit = findShapeAt(e.getX(), e.getY());
                if (hit != null && !hit.authorName().isBlank()) {
                    ownerTooltip.setText("Drawn by: " + hit.authorName());
                    ownerTooltip.show(target, e.getScreenX() + 14, e.getScreenY() + 14);
                } else {
                    ownerTooltip.hide();
                }
            }
        });

        target.setOnMouseEntered(e -> {
            if (activeTool == Tool.ERASER && eraserCursor != null) {
                eraserCursor.setVisible(true);
            }
        });

        target.setOnMouseExited(e -> {
            ownerTooltip.hide();
            if (eraserCursor != null) eraserCursor.setVisible(false);
        });
    }

    // ── Transient-canvas drawing ──────────────────────────────────────────────

    /** Draws a dashed rubber-band ghost of the current LINE or CIRCLE drag. */
    private void drawRubberBandPreview() {
        double dx    = dragCurrentX - dragStartX;
        double dy    = dragCurrentY - dragStartY;
        double width = strokeSlider.getValue();
        Color  color = colorPicker.getValue();

        transientGc.save();
        transientGc.setGlobalAlpha(0.60);
        transientGc.setStroke(color);
        transientGc.setLineWidth(width);
        transientGc.setLineDashes(7, 5);

        switch (activeTool) {
            case LINE ->
                transientGc.strokeLine(dragStartX, dragStartY, dragCurrentX, dragCurrentY);
            case CIRCLE -> {
                double r  = Math.sqrt(dx * dx + dy * dy);
                double ox = dragStartX - r;
                double oy = dragStartY - r;
                transientGc.strokeOval(ox, oy, r * 2, r * 2);
            }
            default -> { /* not used for other tools */ }
        }

        transientGc.restore();
    }

    /** Draws one incremental segment of a freehand / eraser path. */
    private void drawFreehandSegment(double x1, double y1, double x2, double y2) {
        transientGc.save();

        if (activeTool == Tool.ERASER) {
            transientGc.setStroke(Color.WHITE);
            transientGc.setLineWidth(strokeSlider.getValue() * 3.0);
            transientGc.setLineCap(StrokeLineCap.SQUARE);
        } else {
            transientGc.setStroke(colorPicker.getValue());
            transientGc.setLineWidth(strokeSlider.getValue());
            transientGc.setLineCap(StrokeLineCap.ROUND);
        }

        transientGc.setLineDashes((double[]) null);
        transientGc.setLineJoin(StrokeLineJoin.ROUND);
        transientGc.strokeLine(x1, y1, x2, y2);

        transientGc.restore();
    }

    /**
     * Draws a single new segment for a remote FREEHAND or ERASER gesture directly
     * onto {@code remoteTransientGc} <em>without</em> clearing the canvas first.
     *
     * <p>Bridging consecutive 40 ms network ticks this way produces a continuous,
     * gap-free stroke instead of the dotted-line artefact seen when the layer is
     * cleared and fully redrawn on every update.  Because the accumulated
     * {@link TransientShapeEntry#points} list is still maintained, a subsequent
     * call to {@link #renderTransient()} (e.g. on LINE update from another peer)
     * will reconstruct the full path correctly.
     *
     * @param entry the in-progress gesture whose latest segment should be appended
     * @param x1    X of the previous tip (before this update)
     * @param y1    Y of the previous tip (before this update)
     * @param x2    X of the new tip (after this update)
     * @param y2    Y of the new tip (after this update)
     */
    private void drawRemoteSegmentIncremental(TransientShapeEntry entry,
                                              double x1, double y1, double x2, double y2) {
        remoteTransientGc.save();
        remoteTransientGc.setGlobalAlpha(0.75);
        remoteTransientGc.setLineDashes((double[]) null);
        remoteTransientGc.setLineJoin(StrokeLineJoin.ROUND);
        if ("ERASER".equals(entry.tool)) {
            remoteTransientGc.setStroke(Color.WHITE);
            remoteTransientGc.setLineWidth(entry.strokeWidth);
            remoteTransientGc.setLineCap(StrokeLineCap.SQUARE);
        } else {
            remoteTransientGc.setStroke(parseColor(entry.color));
            remoteTransientGc.setLineWidth(entry.strokeWidth);
            remoteTransientGc.setLineCap(StrokeLineCap.ROUND);
        }
        remoteTransientGc.strokeLine(x1, y1, x2, y2);
        remoteTransientGc.restore();
    }

    /**
     * Builds the floating JavaFX overlay node used to display a remote peer's
     * live-typing session on {@code cursorPane}.  The node is positioned and
     * updated in-place by the {@link CanvasUpdateListener#onTextUpdate} callback.
     *
     * <p>Visual structure (top→bottom):
     * <pre>
     *  ┌───┬──────────────┐  ← HBox badge
     *  │ █ │  AuthorName  │    (colored stripe + dark name pill)
     *  └───┴──────────────┘
     *  ┌────────────────────┐ ← Label textPreview
     *  │  current text▏     │   (accent-colored, dark translucent bg)
     *  └────────────────────┘
     * </pre>
     *
     * @param authorName display name of the typing peer
     * @param clientId   session identifier — hashed to produce a deterministic accent colour
     * @return a mouse-transparent {@link VBox} ready to be added to {@code cursorPane}
     */
    private VBox buildGhostTextNode(String authorName, String clientId) {
        // Derive a stable, visually distinct hue from clientId so the same peer
        // always gets the same colour across all observers.
        int    hash   = clientId.hashCode();
        double hue    = (hash & 0x7FFF_FFFF) % 360.0;
        Color  accent = Color.hsb(hue, 0.65, 0.95);
        String hex    = toHexString(accent);

        // Left accent stripe (mirrors the drawing-cursor badge style in renderTransient)
        Region stripe = new Region();
        stripe.setPrefWidth(3);
        stripe.setPrefHeight(16);
        stripe.setStyle("-fx-background-color: " + hex + "; -fx-background-radius: 2 0 0 2;");

        // Author name pill
        Label nameTag = new Label(authorName.isBlank() ? "typing…" : authorName);
        nameTag.setStyle(
            "-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;" +
            "-fx-padding: 1 5 1 4; -fx-background-color: rgba(8,8,20,0.82);" +
            "-fx-background-radius: 0 3 3 0;");

        HBox badge = new HBox(0, stripe, nameTag);
        badge.setAlignment(Pos.CENTER_LEFT);

        // Live-text preview: shows the in-progress content with a thin block cursor
        Label textPreview = new Label("\u258f");   // initial cursor glyph
        textPreview.setStyle(
            "-fx-text-fill: " + hex + "; -fx-font-size: 14px;" +
            "-fx-padding: 2 6 2 4; -fx-background-color: rgba(8,8,20,0.60);" +
            "-fx-background-radius: 0 3 3 3;");

        VBox box = new VBox(0, badge, textPreview);
        box.setMouseTransparent(true);
        return box;
    }

    // ── Shape commit ─────────────────────────────────────────────────────────

    /**
     * Builds the final {@link Shape}(s) from the completed drag gesture,
     * stores them locally, and enqueues them for network broadcast.
     */
    private void commitShape(double endX, double endY) {
        String color       = toHexString(colorPicker.getValue());
        double strokeWidth = strokeSlider.getValue();

        switch (activeTool) {
            case LINE -> {
                double dx = endX - dragStartX;
                double dy = endY - dragStartY;
                if (Math.sqrt(dx * dx + dy * dy) < MIN_DRAG_DIST) return;
                addAndSend(Line.create(color, dragStartX, dragStartY, endX, endY, strokeWidth,
                                       authorName, clientId));
            }
            case CIRCLE -> {
                double dx = endX - dragStartX;
                double dy = endY - dragStartY;
                double r  = Math.sqrt(dx * dx + dy * dy);
                if (r < MIN_DRAG_DIST) return;
                addAndSend(Circle.create(color, dragStartX, dragStartY, Math.max(1.0, r),
                                         authorName, clientId));
            }
            case FREEHAND -> {
                if (freehandPoints.size() < 2) return;
                for (int i = 1; i < freehandPoints.size(); i++) {
                    double[] p0 = freehandPoints.get(i - 1);
                    double[] p1 = freehandPoints.get(i);
                    addAndSend(Line.create(color, p0[0], p0[1], p1[0], p1[1], strokeWidth,
                                           authorName, clientId));
                }
                freehandPoints.clear();
            }
            case ERASER -> {
                if (freehandPoints.size() < 2) return;
                double eraserWidth = strokeSlider.getValue() * 3.0;
                int n = freehandPoints.size();
                double[] xs = new double[n];
                double[] ys = new double[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = freehandPoints.get(i)[0];
                    ys[i] = freehandPoints.get(i)[1];
                }
                addAndSend(EraserPath.create(xs, ys, eraserWidth, authorName, clientId));
                freehandPoints.clear();
            }
        }
    }

    private void addAndSend(Shape shape) {
        shapes.put(shape.objectId(), shape);
        undoHistory.addLast(shape.objectId());
        if (networkClient != null) {
            try {
                networkClient.sendMutation(shape);
            } catch (Exception ex) {
                log.warn("sendMutation failed: {}", ex.getMessage());
            }
        }
    }

    /**
     * Removes all shapes owned by this client, notifies the server so all peers
     * receive a {@code CLEAR_USER_SHAPES} broadcast, and empties the local undo
     * history.  Only this user's shapes are affected; other users' shapes remain.
     */
    private void clearBoard() {
        // Send the scoped clear to the server first; peers receive the broadcast
        // and remove only this user's shapes via onUserShapesCleared().
        if (networkClient != null) {
            try {
                networkClient.sendClearUserShapes();
            } catch (Exception ex) {
                log.warn("sendClearUserShapes failed: {}", ex.getMessage());
            }
        }
        // Offline fallback: remove only this user's shapes so the canvas reflects
        // the scoped semantics even without a server echo.
        shapes.values().removeIf(s -> s.clientId().equals(clientId));
        undoHistory.clear();
        redrawBaseCanvas(shapes.values());
    }

    /**
     * Removes the most-recently-committed local shape from the canvas and
     * sends an {@code UNDO_REQUEST} to the server so peers also remove it.
     * If no undoable shape exists this is a silent no-op.
     */
    private void undoLastShape() {
        UUID lastId = undoHistory.pollLast();
        if (lastId == null) {
            log.debug("Nothing to undo");
            return;
        }
        shapes.remove(lastId);
        redrawBaseCanvas(shapes.values());
        if (networkClient != null) {
            try {
                networkClient.sendUndoRequest(lastId);
            } catch (Exception ex) {
                log.warn("sendUndoRequest failed: {}", ex.getMessage());
            }
        }
        log.debug("Undo applied locally shapeId={}", lastId);
    }

    private void notifyUdpMove(double x, double y) {
        if (udpTracker != null) udpTracker.onMouseMoved(x, y);
    }

    // ── Text Tool ─────────────────────────────────────────────────────────────

    /**
     * Places a floating {@link TextField} on {@code controlPane} at the given
     * canvas coordinates.  The field uses the currently selected color and a
     * matching font size.
     *
     * <ul>
     *   <li><b>Enter</b> — commits the text as a {@link TextNode}, sends it to
     *       the network, and removes the field.</li>
     *   <li><b>Escape</b> — cancels without committing.</li>
     * </ul>
     *
     * Any previously active TextField is silently dismissed before the new one
     * is added.
     */
    private void placeTextField(double x, double y) {
        dismissActiveTextField();

        // Stable identity for this typing session — used as the TEXT_UPDATE objectId
        // and also sent via SHAPE_COMMIT when the user confirms or cancels, so remote
        // peers know to dismiss the ghost overlay.
        activeTextId = UUID.randomUUID();
        final UUID textId = activeTextId;

        String hexColor = toHexString(colorPicker.getValue());
        int    fontSize = TextNode.create(hexColor, 0, 0, "x").fontSize(); // canonical size (14)
        // Canvas baseline: fillText() renders from the bottom of the glyph, so
        // we offset by fontSize to align the top of the text with the click point.
        final double textBase = y + fontSize;

        TextField textField = new TextField();
        textField.setLayoutX(x);
        textField.setLayoutY(y);
        textField.setPrefWidth(220);
        textField.setStyle(
            "-fx-background-color: rgba(30,30,46,0.55);" +
            "-fx-text-fill: "      + hexColor + ";" +
            "-fx-border-color: "   + hexColor + ";" +
            "-fx-border-width: 0 0 2 0;" +
            "-fx-border-radius: 0;" +
            "-fx-background-radius: 0;" +
            "-fx-font-size: "      + fontSize + "px;" +
            "-fx-padding: 2 4 2 4;"
        );

        controlPane.getChildren().add(textField);
        controlPane.setMouseTransparent(false);
        textField.requestFocus();

        // Throttled TEXT_UPDATE: broadcast at most once every 50 ms so remote peers
        // see live keystrokes almost instantly without flooding the network.
        final long[] lastTextSend = {0L};
        textField.textProperty().addListener((obs, oldVal, newVal) -> {
            long now = System.currentTimeMillis();
            if (networkClient != null && now - lastTextSend[0] > 50) {
                lastTextSend[0] = now;
                try {
                    networkClient.sendTextUpdate(textId, x, textBase, newVal);
                } catch (Exception ex) {
                    log.warn("sendTextUpdate failed: {}", ex.getMessage());
                }
            }
        });

        textField.setOnKeyPressed(keyEvent -> {
            switch (keyEvent.getCode()) {
                case ENTER -> {
                    String text = textField.getText().strip();
                    if (!text.isEmpty()) {
                        // fillText() baseline is at y; offset by fontSize so the
                        // rendered text aligns with the top of the TextField.
                        TextNode node = TextNode.create(
                            toHexString(colorPicker.getValue()),
                            x, textBase,
                            text, authorName, clientId
                        );
                        addAndSend(node);
                    }
                    // Signal remote peers to dismiss the ghost overlay for this session.
                    if (networkClient != null) {
                        try { networkClient.sendShapeCommit(textId); }
                        catch (Exception ex) { log.warn("sendShapeCommit(text) failed: {}", ex.getMessage()); }
                    }
                    controlPane.getChildren().remove(textField);
                    controlPane.setMouseTransparent(true);
                    activeTextId = null;
                    keyEvent.consume();
                }
                case ESCAPE -> {
                    // Cancel: still dismiss the ghost so peers don't see a stale overlay.
                    if (networkClient != null) {
                        try { networkClient.sendShapeCommit(textId); }
                        catch (Exception ex) { log.warn("sendShapeCommit(text cancel) failed: {}", ex.getMessage()); }
                    }
                    controlPane.getChildren().remove(textField);
                    controlPane.setMouseTransparent(true);
                    activeTextId = null;
                    keyEvent.consume();
                }
                default -> { /* let the TextField handle normal typing */ }
            }
        });
    }

    /**
     * Removes any active floating {@link TextField} from {@code controlPane},
     * restores the pane to its default mouse-transparent state, and sends a
     * {@code SHAPE_COMMIT} for the current text session so remote peers dismiss
     * their ghost overlay.  Safe to call when no field is present.
     */
    private void dismissActiveTextField() {
        if (controlPane != null && !controlPane.getChildren().isEmpty()) {
            if (activeTextId != null && networkClient != null) {
                final UUID id = activeTextId;
                try { networkClient.sendShapeCommit(id); }
                catch (Exception ex) { log.warn("sendShapeCommit(dismiss) failed: {}", ex.getMessage()); }
            }
            activeTextId = null;
            controlPane.getChildren().clear();
            controlPane.setMouseTransparent(true);
        }
    }

    // =========================================================================
    // Networking
    // =========================================================================

    private void initNetworking() {
        Parameters   params = getParameters();
        List<String> raw    = params.getRaw();
        String host = raw.size() > 0 ? raw.get(0) : DEFAULT_HOST;
        int    port = raw.size() > 1 ? parseInt(raw.get(1), DEFAULT_PORT) : DEFAULT_PORT;

        networkClient = new NetworkClient(host, port, authorName, clientId);

        // Callbacks arrive on distrisync-read; marshal to FX thread before touching state
        networkClient.addListener(new CanvasUpdateListener() {

            @Override
            public void onSnapshotReceived(List<Shape> incoming) {
                Platform.runLater(() -> {
                    shapes.clear();
                    incoming.forEach(s -> shapes.put(s.objectId(), s));
                    redrawBaseCanvas(shapes.values());
                    log.info("Snapshot applied — {} shape(s) on canvas", shapes.size());
                });
            }

            @Override
            public void onMutationReceived(Shape shape) {
                Platform.runLater(() -> {
                    shapes.put(shape.objectId(), shape);
                    redrawBaseCanvas(shapes.values());
                });
            }

            // ── Live-drawing callbacks ─────────────────────────────────────────

            @Override
            public void onShapeStart(UUID shapeId, String tool, String color,
                                     double strokeWidth, double x, double y, String authorName) {
                Platform.runLater(() -> {
                    transientShapes.put(shapeId,
                            new TransientShapeEntry(shapeId, tool, color, strokeWidth, x, y, authorName));
                    renderTransient();
                });
            }

            @Override
            public void onShapeUpdate(UUID shapeId, double x, double y) {
                Platform.runLater(() -> {
                    TransientShapeEntry entry = transientShapes.get(shapeId);
                    if (entry != null) {
                        if ("FREEHAND".equals(entry.tool) || "ERASER".equals(entry.tool)) {
                            // Incremental path: draw only the new segment directly onto
                            // remoteTransientGc without clearing the canvas.  This bridges
                            // the ~40 ms gaps between network ticks and produces a smooth,
                            // gap-free stroke instead of the previous dotted-line artefact.
                            double prevX = entry.lastX;
                            double prevY = entry.lastY;
                            entry.update(x, y);
                            drawRemoteSegmentIncremental(entry, prevX, prevY, x, y);
                        } else {
                            // Rubber-band tools (LINE / CIRCLE) must clear and redraw the
                            // entire transient layer because the shape bounding box changes
                            // on every tick.
                            entry.update(x, y);
                            renderTransient();
                        }
                    }
                });
            }

            @Override
            public void onShapeCommit(UUID shapeId) {
                Platform.runLater(() -> {
                    transientShapes.remove(shapeId);

                    // shapeId doubles as the objectId for live-text sessions: remove
                    // any ghost overlay that was tracking this typing session.
                    VBox ghostNode = ghostTextNodes.remove(shapeId);
                    if (ghostNode != null) {
                        cursorPane.getChildren().remove(ghostNode);
                    }

                    renderTransient();
                    // Committed shapes will arrive via onMutationReceived shortly;
                    // force an immediate redraw so the base layer is not stale.
                    redrawBaseCanvas(shapes.values());
                });
            }

            @Override
            public void onUserShapesCleared(String targetClientId) {
                Platform.runLater(() -> {
                    shapes.values().removeIf(s -> s.clientId().equals(targetClientId));
                    // Only discard this client's undo history when the clear is for us.
                    if (targetClientId.equals(clientId)) {
                        undoHistory.clear();
                    }
                    // redrawBaseCanvas fills white then repaints all remaining shapes;
                    // no need for a separate clearRect/fillRect call.
                    redrawBaseCanvas(shapes.values());
                    renderTransient();
                    log.info("User shapes cleared — clientId='{}'", targetClientId);
                });
            }

            @Override
            public void onShapeDeleted(UUID shapeId) {
                Platform.runLater(() -> {
                    shapes.remove(shapeId);
                    redrawBaseCanvas(shapes.values());
                    log.debug("Shape deleted by remote peer shapeId={}", shapeId);
                });
            }

            @Override
            public void onTextUpdate(UUID objectId, String clientId, String authorName,
                                     double x, double y, String currentText) {
                Platform.runLater(() -> {
                    VBox ghost = ghostTextNodes.get(objectId);
                    if (ghost == null) {
                        ghost = buildGhostTextNode(authorName, clientId);
                        ghostTextNodes.put(objectId, ghost);
                        cursorPane.getChildren().add(ghost);
                    }
                    // Position the overlay just above the text insertion point.
                    ghost.setLayoutX(x);
                    ghost.setLayoutY(y - 34);
                    // Update the live-text preview label (second child of the VBox).
                    Label textPreview = (Label) ghost.getChildren().get(1);
                    textPreview.setText(currentText.isEmpty() ? "\u258f" : currentText + "\u258f");
                });
            }
        });

        // Connect asynchronously — UI is never blocked
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

        // UDP pointer tracker — manages its own threads and the cursorPane nodes
        udpTracker = new UdpPointerTracker(cursorPane);
        udpTracker.setAuthorName(authorName);
        udpTracker.start();
    }

    private void setStatus(String text, String colorHex) {
        statusLabel.setText(text);
        statusLabel.setStyle("-fx-text-fill: " + colorHex + "; -fx-font-size: 12px;");
    }

    // =========================================================================
    // Render loop  (baseCanvas only — transient and cursor layers are event-driven)
    // =========================================================================

    private void startRenderLoop() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                renderBase();
            }
        }.start();
    }

    /**
     * Canonical base-canvas repaint.  Clears the canvas, fills the white
     * background, then draws every shape in causal (Lamport timestamp) order
     * so eraser strokes always paint over earlier shapes.
     *
     * <p>Must be called on the FX Application Thread.  Invoke via
     * {@link Platform#runLater} from any background callback — e.g. inside
     * {@code onSnapshotReceived}, {@code onMutationReceived},
     * {@code onShapeDeleted}, and {@code onUserShapesCleared} — to guarantee the
     * canvas reflects the latest committed state immediately rather than
     * waiting for the next {@link AnimationTimer} tick.
     *
     * @param shapesToDraw the committed shapes to render; must not be {@code null}
     */
    private void redrawBaseCanvas(Collection<Shape> shapesToDraw) {
        double w = baseCanvas.getWidth();
        double h = baseCanvas.getHeight();

        baseGc.setFill(Color.WHITE);
        baseGc.fillRect(0, 0, w, h);

        shapesToDraw.stream()
                    .sorted(Comparator.comparingLong(Shape::timestamp))
                    .forEach(s -> drawShape(baseGc, s));
    }

    /**
     * Animation-timer entry point — delegates to {@link #redrawBaseCanvas}
     * so the canvas is refreshed every frame even when no network event fired.
     */
    private void renderBase() {
        redrawBaseCanvas(shapes.values());
    }

    /**
     * Clears and redraws {@code remoteTransientCanvas} with all currently
     * tracked in-progress shapes from remote peers.  Must be called on the
     * FX Application Thread.
     *
     * <p>This canvas sits between the base layer and the local transient layer,
     * so clearing it never disturbs the local rubber-band / freehand preview.
     */
    private void renderTransient() {
        double w = remoteTransientCanvas.getWidth();
        double h = remoteTransientCanvas.getHeight();
        remoteTransientGc.clearRect(0, 0, w, h);

        for (TransientShapeEntry entry : transientShapes.values()) {
            remoteTransientGc.save();
            remoteTransientGc.setGlobalAlpha(0.75);
            remoteTransientGc.setLineDashes((double[]) null);
            remoteTransientGc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            remoteTransientGc.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);

            switch (entry.tool) {
                case "LINE" -> {
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.strokeLine(entry.startX, entry.startY,
                                                  entry.lastX,  entry.lastY);
                }
                case "CIRCLE" -> {
                    double dx = entry.lastX - entry.startX;
                    double dy = entry.lastY - entry.startY;
                    double r  = Math.sqrt(dx * dx + dy * dy);
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.strokeOval(entry.startX - r, entry.startY - r, r * 2, r * 2);
                }
                case "FREEHAND" -> {
                    remoteTransientGc.setStroke(parseColor(entry.color));
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    List<double[]> pts = entry.points;
                    for (int i = 1; i < pts.size(); i++) {
                        double[] p0 = pts.get(i - 1);
                        double[] p1 = pts.get(i);
                        remoteTransientGc.strokeLine(p0[0], p0[1], p1[0], p1[1]);
                    }
                }
                case "ERASER" -> {
                    remoteTransientGc.setStroke(Color.WHITE);
                    remoteTransientGc.setLineWidth(entry.strokeWidth);
                    remoteTransientGc.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);
                    List<double[]> pts = entry.points;
                    for (int i = 1; i < pts.size(); i++) {
                        double[] p0 = pts.get(i - 1);
                        double[] p1 = pts.get(i);
                        remoteTransientGc.strokeLine(p0[0], p0[1], p1[0], p1[1]);
                    }
                }
                default -> { /* unknown tool — skip */ }
            }

            // ── Figma-style author attribution label ──────────────────────────
            // Rendered at the tip of the in-progress shape so every observer
            // can see which peer is drawing at a glance.
            if (entry.authorName != null && !entry.authorName.isBlank()) {
                String label   = entry.authorName;
                double lx      = entry.lastX + 10;
                double ly      = entry.lastY - 10;
                double approxW = label.length() * 6.8;

                remoteTransientGc.save();
                remoteTransientGc.setGlobalAlpha(0.88);
                // Dark pill background
                remoteTransientGc.setFill(Color.color(0.08, 0.08, 0.12, 0.78));
                remoteTransientGc.fillRoundRect(lx - 3, ly - 13, approxW + 10, 17, 5, 5);
                // Coloured left accent stripe
                remoteTransientGc.setFill(parseColor(entry.color));
                remoteTransientGc.fillRoundRect(lx - 3, ly - 13, 3, 17, 2, 2);
                // Label text in white
                remoteTransientGc.setFont(Font.font("System", FontWeight.BOLD, 11));
                remoteTransientGc.setFill(Color.WHITE);
                remoteTransientGc.fillText(label, lx + 4, ly);
                remoteTransientGc.restore();
            }

            remoteTransientGc.restore();
        }
    }

    // =========================================================================
    // Shape rendering
    // =========================================================================

    private void drawShape(GraphicsContext gc, Shape shape) {
        gc.save();

        switch (shape) {
            case Line l -> {
                gc.setStroke(parseColor(l.color()));
                gc.setLineWidth(l.strokeWidth());
                gc.setLineDashes((double[]) null);
                gc.setLineCap(StrokeLineCap.ROUND);
                gc.setLineJoin(StrokeLineJoin.ROUND);
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
                    gc.setLineDashes((double[]) null);
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
            case EraserPath ep -> {
                // Render as a white stroke with a square brush.
                // Because redrawBaseCanvas always fills white before painting shapes in
                // Lamport-timestamp order, this white path pixel-perfectly overwrites all
                // shapes with an earlier timestamp — functionally identical to BlendMode.ERASE
                // on a white-background canvas (JavaFX's BlendMode enum has no ERASE value).
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(ep.strokeWidth());
                gc.setLineDashes((double[]) null);
                gc.setLineCap(StrokeLineCap.SQUARE);
                gc.setLineJoin(StrokeLineJoin.ROUND);
                double[] xs = ep.xs();
                double[] ys = ep.ys();
                for (int i = 1; i < xs.length; i++) {
                    gc.strokeLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
                }
            }
        }

        gc.restore();
    }

    // =========================================================================
    // Hit-testing (hover ownership)
    // =========================================================================

    /**
     * Returns the topmost committed {@link Shape} whose geometry intersects
     * the given canvas point, or {@code null} if none does.
     * "Topmost" is defined as the shape with the highest Lamport timestamp
     * (i.e. the most recently drawn).
     *
     * @param x canvas X coordinate
     * @param y canvas Y coordinate
     */
    private Shape findShapeAt(double x, double y) {
        return shapes.values().stream()
                     .filter(s -> hitsShape(x, y, s))
                     .max(Comparator.comparingLong(Shape::timestamp))
                     .orElse(null);
    }

    /**
     * Returns {@code true} when the point {@code (x, y)} lies within the
     * interactive hit area of {@code shape}.
     *
     * <ul>
     *   <li><b>Line</b> — within half the stroke width plus a 5 px tolerance
     *       of the closest point on the segment.</li>
     *   <li><b>Circle</b> — inside the radius (filled) or within half the
     *       stroke width of the circumference (hollow).</li>
     *   <li><b>TextNode</b> — within an approximate bounding rectangle.</li>
     * </ul>
     */
    private boolean hitsShape(double x, double y, Shape s) {
        final double TOL = 5.0;
        return switch (s) {
            case Line l -> {
                double dx    = l.x2() - l.x1();
                double dy    = l.y2() - l.y1();
                double lenSq = dx * dx + dy * dy;
                if (lenSq == 0) {
                    yield Math.hypot(x - l.x1(), y - l.y1()) <= l.strokeWidth() / 2 + TOL;
                }
                double t    = Math.max(0, Math.min(1,
                              ((x - l.x1()) * dx + (y - l.y1()) * dy) / lenSq));
                double projX = l.x1() + t * dx;
                double projY = l.y1() + t * dy;
                yield Math.hypot(x - projX, y - projY) <= l.strokeWidth() / 2 + TOL;
            }
            case Circle c -> {
                double dist = Math.hypot(x - c.x(), y - c.y());
                if (c.filled()) yield dist <= c.radius() + TOL;
                yield Math.abs(dist - c.radius()) <= c.strokeWidth() / 2 + TOL;
            }
            case TextNode t -> {
                double approxW = t.content().length() * t.fontSize() * 0.6;
                yield x >= t.x() - TOL
                   && x <= t.x() + approxW + TOL
                   && y >= t.y() - t.fontSize() - TOL
                   && y <= t.y() + TOL;
            }
            case EraserPath ep -> false;  // Eraser strokes have no interactive hit area
        };
    }

    // =========================================================================
    // Eraser cursor
    // =========================================================================

    /**
     * Creates the MS-Paint-style square eraser cursor node and adds it to
     * {@code cursorPane}.  The node's size is bound to the stroke slider so it
     * updates in real time as the user drags the slider.
     *
     * <p>Must be called after both {@code cursorPane} and {@code strokeSlider}
     * are initialised (i.e. after {@link #buildToolbar()} and the canvas stack
     * are set up in {@link #start}).
     */
    private void setupEraserCursor() {
        eraserCursor = new Rectangle();
        // Width and height track strokeSlider * 3.0, identical to the committed stroke size
        eraserCursor.widthProperty().bind(strokeSlider.valueProperty().multiply(3.0));
        eraserCursor.heightProperty().bind(strokeSlider.valueProperty().multiply(3.0));
        eraserCursor.setFill(Color.TRANSPARENT);
        eraserCursor.setStroke(Color.BLACK);
        eraserCursor.setStrokeWidth(1.5);
        eraserCursor.setMouseTransparent(true);
        eraserCursor.setVisible(false);
        cursorPane.getChildren().add(eraserCursor);
    }

    /**
     * Moves the eraser cursor square so its centre aligns with the given canvas
     * coordinates, and makes it visible when the Eraser tool is active.
     *
     * @param x canvas X coordinate of the current mouse position
     * @param y canvas Y coordinate of the current mouse position
     */
    private void updateEraserCursorPosition(double x, double y) {
        if (eraserCursor == null || activeTool != Tool.ERASER) return;
        eraserCursor.setVisible(true);
        double half = eraserCursor.getWidth() / 2.0;
        eraserCursor.setLayoutX(x - half);
        eraserCursor.setLayoutY(y - half);
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
            (int) Math.round(c.getBlue()  * 255));
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

    // =========================================================================
    // TransientShapeEntry — per-peer mutable in-progress drawing record
    // (FX Application Thread only — always accessed inside Platform.runLater)
    // =========================================================================

    /**
     * Holds the mutable state for a remote peer's in-progress drawing gesture.
     * Instances are created on {@code SHAPE_START} and removed on
     * {@code SHAPE_COMMIT}.  All reads and writes occur on the FX Application
     * Thread, so no synchronization is needed.
     */
    private static final class TransientShapeEntry {

        final UUID         shapeId;
        final String       tool;
        final String       color;
        final double       strokeWidth;
        final double       startX;
        final double       startY;
        /** Display name of the remote peer who owns this in-progress gesture. */
        final String       authorName;
        double             lastX;
        double             lastY;
        /** Accumulated points — only populated for FREEHAND / ERASER gestures. */
        final List<double[]> points = new ArrayList<>();

        TransientShapeEntry(UUID shapeId, String tool, String color,
                            double strokeWidth, double x, double y, String authorName) {
            this.shapeId     = shapeId;
            this.tool        = tool;
            this.color       = color;
            this.strokeWidth = strokeWidth;
            this.startX      = x;
            this.startY      = y;
            this.authorName  = authorName != null ? authorName : "";
            this.lastX       = x;
            this.lastY       = y;
            if ("FREEHAND".equals(tool) || "ERASER".equals(tool)) {
                points.add(new double[]{x, y});
            }
        }

        /** Updates the tip position; appends to the point list for path-based tools. */
        void update(double x, double y) {
            lastX = x;
            lastY = y;
            if ("FREEHAND".equals(tool) || "ERASER".equals(tool)) {
                points.add(new double[]{x, y});
            }
        }
    }
}
