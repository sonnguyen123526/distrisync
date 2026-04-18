# DistriSync — Distributed Collaborative Whiteboard

**Authors:** Harrison Nguyen & Son Nguyen

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-21.0.4-blue?logo=java&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.x-C71A36?logo=apachemaven&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Project Overview

DistriSync is a peer-class distributed collaborative whiteboard built entirely on a custom Java NIO server — no third-party real-time framework. The server runs a single-threaded `java.nio.channels.Selector` event loop that accepts connections, multiplexes reads and writes, and fans out shape mutations to all connected clients with zero blocking I/O on the hot path. Accepted `SocketChannel` instances are configured with `TCP_NODELAY = true` and 64 KiB socket buffers to minimise Nagle-induced latency, enabling sub-frame delivery of stroke data across a local network. A length-prefixed binary frame (1-byte type tag + 4-byte big-endian payload length + UTF-8 JSON body) with a 16 MiB hard ceiling governs every exchange between server and clients, providing deterministic parse complexity regardless of payload size.

The client UI is built on JavaFX 21 and styled with a Tailwind-inspired `styles.css` design system — a flat, neutral-toned control panel with uniform spacing and hover states. A layered canvas architecture (base paint layer → remote transient layer → local transient layer → cursor overlay → control pane) ensures that in-progress strokes from remote peers are rendered live without flickering or compositing artefacts. A parallel UDP multicast channel (`239.255.42.42:9292`) carries ephemeral pointer positions at 20 Hz, giving all peers real-time cursor presence without touching the TCP state machine. Shared canvas authority is implemented via last-writer-wins using Lamport-ish per-shape timestamps enforced atomically inside a `ConcurrentHashMap`, so concurrent edits converge without a central lock.

**Workspace Boards:** Rooms now support multiple isolated boards within a single shared space. Each board maintains its own independent `CanvasStateManager` and Write-Ahead Log (`{roomId}_{boardId}.wal`), enabling zero-overhead board creation and persistent recovery. Clients receive a `BOARD_LIST_UPDATE` on join and whenever a new board is created; the `SWITCH_BOARD` message allows in-room navigation between boards. Shape mutations are scoped to the active board and broadcast only to connected peers viewing the same board, preventing cross-board interference. A Figma-style Task View board switcher with live thumbnails facilitates rapid board discovery and navigation.

---

## Core Features (Implemented)

- **Custom Java NIO Server** — single-threaded `Selector` loop; non-blocking `SocketChannel` per client; write queue with `OP_WRITE` only when the send buffer saturates; `TCP_NODELAY` + 64 KiB `SO_SNDBUF`/`SO_RCVBUF` for low-latency streaming state.

- **Binary Length-Prefixed Protocol** — `MessageCodec` frames every message as `[type: 1B][length: 4B big-endian][UTF-8 JSON payload]`; partial-read detection via `PartialMessageException` with buffer compaction; max payload 16 MiB.

- **Sealed Shape Hierarchy** — `Shape` is a Java 21 sealed interface permitting `Line`, `Circle`, `EraserPath`, and `TextNode`; each record carries `objectId` (UUID), Lamport `timestamp`, `color`, `strokeWidth`, `authorName`, and `clientId`. Server-side `ShapeCodec` and a mirrored client `ClientShapeCodec` deserialise via a `"_type"` discriminator field with Gson.

- **Live Collaborative Drawing (`SHAPE_START` / `SHAPE_UPDATE` / `SHAPE_COMMIT`)** — streaming stroke events are relayed to all peers by the server without persistence; the receiving client renders a `TransientShapeEntry` on the remote transient canvas layer, providing smooth live-ink preview before commit.

- **MS Paint–Style Text Tool** — clicking the canvas in `TEXT` mode opens a floating `TextField` directly on the control pane. Keystrokes are throttled and transmitted as `TEXT_UPDATE` frames (~50 ms interval); peers render a ghost `VBox` with a live caret (`▏`) on their cursor pane. Pressing Enter commits a `TextNode` via `MUTATION` + `SHAPE_COMMIT`, which dismisses all ghost previews.

- **`BlendMode.ERASE` Vector Masking (Eraser Tool)** — each eraser gesture is committed as an `EraserPath` shape (parallel `double[] xs`, `ys` coordinate arrays, `strokeWidth = 3×` the stroke slider, square `StrokeLineCap`). The base canvas redraws all shapes sorted by timestamp; white eraser paths painted last visually erase earlier ink without destroying the underlying vector geometry. Live eraser strokes stream as `SHAPE_UPDATE` frames with tool `"ERASER"` for real-time peer preview.

- **Scoped `CLEAR_USER_SHAPES`** — the "Clear Board" button sends a `CLEAR_USER_SHAPES` frame carrying only the issuing client's `clientId`. The server calls `CanvasStateManager.clearUserShapes(clientId)` and broadcasts the scoped clear to peers, who purge only that owner's shapes. No other client's work is affected.

- **Undo (`UNDO_REQUEST` / `SHAPE_DELETE`)** — the client sends an `UNDO_REQUEST`; the server calls `deleteShape` on the state manager and, on success, broadcasts a `SHAPE_DELETE` frame so all peers remove the shape atomically.

- **Figma-Style Live Attribution** — in-progress remote strokes display a floating author label rendered at the stroke tip from `TransientShapeEntry.authorName` (sourced from the `SHAPE_START` handshake). Committed shapes surface the author name in a hover tooltip via `findShapeAt` hit-testing + `ownerTooltip` overlay.

- **UDP Multicast Cursor Presence** — `UdpPointerTracker` joins multicast group `239.255.42.42:9292`; pointer positions are transmitted at 20 Hz (only on mouse move). Each peer is represented by a deterministically coloured dot + name badge on a dedicated `cursorPane` with fade-out removal on timeout.

- **Pointer State Management** — `PointerStateManager` maintains a `ConcurrentHashMap<String, PointerState>` of active remote cursors with a clock-injectable eviction model. `PointerState` is an immutable record (`clientId`, `x`, `y`, `lastUpdatedAt`). Entries older than 500 ms are removed by `evictStalePointers()`, which is driven by the JavaFX `AnimationTimer` render loop, keeping cursor overlays consistent with actual presence.

- **Session Multiplexing (Rooms & Boards)** — `RoomManager` maintains a `ConcurrentHashMap<String, RoomContext>` of isolated rooms; each `RoomContext` hosts a `ConcurrentHashMap<String, CanvasStateManager>` of independent boards (created lazily per board ID on first access). Rooms are created on first client join and persist even when empty, allowing clients to rejoin and find their original canvas state. Within each room, clients switch boards via `SWITCH_BOARD`, triggering a fresh `SNAPSHOT` and optional `BOARD_LIST_UPDATE`. `NioServer` routes all mutations, live strokes, and relayed frames to peers on the same board (not just the room), providing true board-level isolation.

- **Write-Ahead Log (WAL) with Crash Recovery** — `WalManager` appends every accepted state-mutating frame (`MUTATION`, `SHAPE_DELETE`, `CLEAR_USER_SHAPES`) to a per-room, per-board `{roomId}_{boardId}.wal` file under `distrisync-data/` using `FileChannel` in `APPEND` mode. Boards are replayed lazily when first opened via `RoomContext.getBoard(boardId)`: frames are decoded sequentially from a heap `ByteBuffer`; a truncated tail frame — the typical artefact of a mid-write crash — is silently tolerated and recovery stops at the corrupt offset, returning the clean prefix. Room and board identifiers are sanitised (`[^a-zA-Z0-9_\-]` → `_`) before use as filenames to prevent path traversal.

- **Last-Writer-Wins Convergence** — `CanvasStateManager` uses `ConcurrentHashMap.compute` with a strictly-greater-timestamp guard, so concurrent mutations from multiple peers converge without server-side locking or operational transforms.

- **Reconnect with Back-off** — `NetworkClient` reconnects automatically after EOF or I/O errors using a synchronised `reconnect()` cycle that re-executes the full `HANDSHAKE` → `SNAPSHOT` flow to restore canvas state.

- **Lobby Discovery & Room Management (`LOBBY_STATE` / `JOIN_ROOM` / `LEAVE_ROOM`)** — clients connect to a lobby and receive periodic `LOBBY_STATE` broadcasts listing all active rooms with live user counts. `JOIN_ROOM` transitions a client from lobby into a specific room's canvas (accepting a `roomId` and optional `initialBoardId`); `LEAVE_ROOM` returns to the lobby with an empty payload. The server maintains a single global lobby session for discovery and scoped `RoomContext` sessions for drawing, enabling efficient multi-room deployments with fully isolated state per room.

- **Push-to-Talk Voice Chat (`AudioEngine` / `UDP_ADMISSION`)** — `AudioEngine` implements a UDP audio data plane using `javax.sound.sampled` at 8 kHz / 16-bit signed PCM / mono / big-endian (`AUDIO_FORMAT`). Each 10 ms capture frame produces 160 bytes of PCM (`PAYLOAD_SIZE`). Wire datagrams are 196 bytes: a 36-byte null-padded UTF-8 identity token followed by the 160-byte PCM payload. Before audio can flow, the server sends a `UDP_ADMISSION` frame on the TCP channel carrying a `udpToken`; `AudioEngine.onUdpAdmission()` opens a connected `DatagramSocket`, sends a 36-byte registration punch packet, and starts the receive daemon. A dedicated capture thread (`distrisync-audio-capture`) runs at `MAX_PRIORITY`; a permanent receive daemon (`distrisync-audio-recv`) plays back incoming PCM via a lazily opened `SourceDataLine` with a 1 600-byte hardware buffer. The `UserSpeakingListener` functional interface fires on each received packet so the UI can highlight the active speaker.

- **PING/PONG RTT Telemetry** — after `HANDSHAKE` completion, `NetworkClient` starts a daemon thread (`distrisync-ping`) that fires a `PING` frame every 2 000 ms (`HEARTBEAT_PING_INTERVAL_MS`). Each `PING` payload is `{ "t": <originMillis> }` encoded via `MessageCodec.PingPongPayload`. The server echoes the origin timestamp unchanged in a `PONG` response. On receipt, `applyPingRtt(originTimestamp)` computes `RTT = max(0, System.currentTimeMillis() - originTimestamp)` and updates `SimpleLongProperty ping` on the JavaFX Application Thread. `pingProperty()` exposes the last measured RTT in ms (initial value `-1` before first measurement). `ingestPongForTelemetryTest(Message)` is a package-private test hook that drives the full RTT path without a live TCP connection.

- **Server-Side Traffic Metrics Heartbeat** — `NioServer` maintains two lock-free counters: `AtomicLong bytesRouted` accumulates the total octets delivered across all TCP board fan-out writes and UDP audio relay sends (one increment per recipient per frame); `AtomicInteger activeTcpSockets` is incremented on each `OP_ACCEPT` and decremented on each channel close, providing a live socket gauge. A `ScheduledExecutorService` on the `distrisync-traffic-metrics` thread emits a structured `[METRICS]` log line every 10 seconds via `emitTrafficHeartbeat()`, recording `bytesRouted`, `roomManager.getActiveRoomCount()`, and `activeTcpSockets`. No external metrics library is required; Logback with the Jansi ANSI console appender provides coloured, human-readable output.

- **Telemetry HUD** — `WhiteboardApp.wireTelemetryHud(NetworkClient)` constructs a bottom-right overlay `HBox` (CSS classes `.telemetry-hud` + `.telemetry-pill`) with three monospace label segments styled `.telemetry-hud-line`, separated by `.telemetry-hud-sep` dividers. Labels are bound directly to `tcpConnectedProperty()`, `udpActiveProperty()`, and `pingProperty()` via JavaFX property bindings; the ping label displays `"Ping: —"` while the initial value is `< 0` and `"Ping: Nms"` once the first PONG is processed.

- **Collapsible Tools Drawer (`ToolsDrawerToggleModel`)** — drawer open/close state is extracted from `WhiteboardApp` into a pure `ToolsDrawerToggleModel`, making animation geometry (slide target X, chevron labels, panel translate X) fully unit-testable without a JavaFX runtime. The `ToggleRestSnapshot` record captures the complete settled UI state after a toggle for deterministic assertions in `ToolsDrawerToggleModelTest`.

- **Idle Room Eviction & Storage Lifecycle** — `StorageLifecycleManager` is a background daemon that sweeps every 60 seconds, evicting rooms with zero active clients that have been idle longer than 5 minutes. Evicted rooms are removed from the in-memory `RoomManager` registry to reclaim heap; all per-board WAL files are preserved on disk for manual recovery. Connected clients are never interrupted regardless of inactivity, and new boards are created on-demand with no eviction overhead.

---

## Project Structure

```
distrisync/
├── src/
│   ├── main/
│   │   ├── java/com/distrisync/
│   │   │   ├── client/              # JavaFX UI, TCP client, audio, UDP pointer tracker
│   │   │   │   ├── WhiteboardApp.java
│   │   │   │   ├── NetworkClient.java
│   │   │   │   ├── WhiteboardClient.java
│   │   │   │   ├── UdpPointerTracker.java
│   │   │   │   ├── CanvasUpdateListener.java
│   │   │   │   ├── LobbyUpdateListener.java
│   │   │   │   ├── RoomInfo.java
│   │   │   │   ├── PointerState.java
│   │   │   │   ├── PointerStateManager.java
│   │   │   │   ├── AudioEngine.java
│   │   │   │   ├── UserSpeakingListener.java
│   │   │   │   └── ToolsDrawerToggleModel.java
│   │   │   ├── server/              # NIO server, room routing, WAL, canvas authority
│   │   │   │   ├── WhiteboardServer.java
│   │   │   │   ├── NioServer.java
│   │   │   │   ├── RoomManager.java
│   │   │   │   ├── RoomContext.java
│   │   │   │   ├── StorageLifecycleManager.java
│   │   │   │   ├── WalManager.java
│   │   │   │   ├── CanvasStateManager.java
│   │   │   │   ├── ClientSession.java
│   │   │   │   └── ShapeCodec.java
│   │   │   ├── protocol/            # Binary framing and message type registry
│   │   │   │   ├── MessageCodec.java
│   │   │   │   ├── MessageType.java
│   │   │   │   ├── Message.java
│   │   │   │   └── PartialMessageException.java
│   │   │   └── model/               # Sealed shape hierarchy
│   │   │       ├── Shape.java
│   │   │       ├── Line.java
│   │   │       ├── Circle.java
│   │   │       ├── TextNode.java
│   │   │       └── EraserPath.java
│   │   └── resources/
│   │       ├── styles.css
│   │       └── logback.xml
│   └── test/
│       └── java/com/distrisync/
│           ├── client/
│           │   ├── AudioEngineTest.java
│           │   ├── NetworkClientTelemetryTest.java
│           │   ├── PointerStateTrackerTest.java
│           │   └── ToolsDrawerToggleModelTest.java
│           ├── integration/ClientServerIntegrationTest.java
│           ├── protocol/MessageCodecTest.java
│           └── server/
│               ├── CanvasStateManagerTest.java
│               ├── NioServerTest.java
│               ├── NioServerUdpRoutingBufferTest.java
│               ├── RoomContextTest.java
│               ├── RoomManagerTest.java
│               ├── ServerMetricsTest.java
│               └── WalManagerTest.java
├── Dockerfile              # Multi-stage backend image (Maven → Temurin 21 JRE)
├── docker-compose.yml      # distrisync-server: TCP+UDP 9090, WAL volume, restart policy
└── pom.xml
```

---

## Wire Protocol Reference

| `MessageType` | Byte | Direction | Persisted to WAL | Description |
|---|---|---|---|---|
| `HANDSHAKE` | `0x01` | C → S | No | Client identification (`clientId`, `authorName`) |
| `SNAPSHOT` | `0x02` | S → C | No | Full canvas state on connect / reconnect / board switch |
| `MUTATION` | `0x03` | C ↔ S | **Yes** | Committed shape add/update; broadcast to board peers |
| `UDP_POINTER` | `0x04` | UDP only | No | Ephemeral cursor position (multicast, not TCP) |
| `SHAPE_START` | `0x05` | C ↔ S | No | Begin live stroke; relayed to board peers, not persisted |
| `SHAPE_UPDATE` | `0x06` | C ↔ S | No | Incremental stroke points; relayed to board peers, not persisted |
| `SHAPE_COMMIT` | `0x07` | C ↔ S | No | Finalise live stroke; peer dismisses transient layer |
| `CLEAR_USER_SHAPES` | `0x08` | C ↔ S | **Yes** | Remove all shapes owned by a specific `clientId` on active board |
| `UNDO_REQUEST` | `0x09` | C → S | No | Request last-shape deletion from active board |
| `SHAPE_DELETE` | `0x0A` | S → C | **Yes** | Broadcast shape removal after undo |
| `TEXT_UPDATE` | `0x0B` | C ↔ S | No | Live text ghost preview; relayed to board peers, not persisted |
| `LOBBY_STATE` | `0x0C` | S → C | No | JSON array of `{ roomId, userCount }` for room discovery |
| `JOIN_ROOM` | `0x0D` | C → S | No | JSON object `{ roomId, initialBoardId? }` (legacy JSON string roomId accepted); defaults to `Board-1` |
| `LEAVE_ROOM` | `0x0E` | C → S | No | Return from a room to lobby (empty payload) |
| `SWITCH_BOARD` | `0x0F` | C → S | No | JSON string target board id; server responds with `SNAPSHOT` |
| `BOARD_LIST_UPDATE` | `0x10` | S → C | No | JSON array of board id strings actively in use within the room |
| `UDP_ADMISSION` | `0x11` | S → C | No | JSON object `{ udpToken }` granting access to the UDP audio data plane; client calls `AudioEngine.onUdpAdmission()` on receipt |
| `PING` | `0x12` | C → S | No | JSON object `{ "t": <originMillis> }` sent every 2 000 ms by `distrisync-ping` thread; server must be post-handshake |
| `PONG` | `0x13` | S → C | No | JSON object `{ "t": <originMillis> }` — server echoes the origin timestamp unchanged; client computes `RTT = now - t` |

---

## Prerequisites

| Requirement | Minimum Version |
|---|---|
| JDK | 21 (for local builds and the JavaFX client; not required on the host to *run* the server in Docker) |
| Apache Maven | 3.8+ (or use the repository `mvnw` / `mvnw.cmd` wrapper) |
| Docker Desktop (optional) | Recent **Docker Compose** v2 for containerised server |
| Network | Server and clients on the same subnet (UDP multicast for pointer presence) |

> **Note:** For a local (non-Docker) server or client, ensure `JAVA_HOME` points to a JDK 21 installation. From the repository root, prefer the Maven wrapper: **`.\mvnw.cmd`** on Windows, **`./mvnw`** on macOS/Linux (instead of requiring a global `mvn` on `PATH`).

---

## Build & Execution

### 1. Build the Project

Run once from the repository root to compile sources and execute all unit tests:

```powershell
.\mvnw.cmd clean install
```

---

### 2. Start the Server

The server listens on **TCP 9090** for the framed control protocol. The container image also **exposes UDP 9090** so host port mappings can carry server-routed UDP traffic (for example push-to-talk relay) on the same port number alongside TCP.

#### Option A — Docker Compose (backend only)

The root **`Dockerfile`** is a multi-stage build: **`maven:3.9.6-eclipse-temurin-21`** copies **`pom.xml`** first and runs **`mvn -B dependency:go-offline -DskipTests`** so dependency layers cache separately; then **`src/`** is copied and the project is built with **`mvn -B package -DskipTests`**, runtime JARs are copied to **`target/dependency`**, and the main artifact is renamed to **`server.jar`**. The runtime stage uses **`eclipse-temurin:21-jre-jammy`** with **`WORKDIR /app`**, copies **`server.jar`** plus **`lib/`**, and starts **`com.distrisync.server.WhiteboardServer`** on port **9090** with WAL data under **`/app/distrisync-data`** (bind-mounted from the host).

From the repository root:

```powershell
docker compose up --build
```

The Compose file defines a **`distrisync-server`** service that **builds** the local `Dockerfile`, publishes **both protocols** on the host (`9090/tcp` and `9090/udp`), mounts **`./distrisync-data:/app/distrisync-data`** so WAL files survive container recreation, and sets **`restart: unless-stopped`**.

You do not need a local JDK or Maven run to produce the server binary when using this path; the build runs entirely inside the image.

To build the image without starting a long-running container:

```powershell
docker compose build
```

#### Option B — Maven or JAR on the host

The server binds on TCP port **9090** by default. Optional positional arguments override **port** and **WAL directory** (see `WhiteboardServer` usage in source). For local runs, WAL files are typically under **`distrisync-data/`** in the working directory and are created on first use.

**Default port (9090):**

```powershell
.\mvnw.cmd exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer"
```

**Custom port (e.g., 8080):**

```powershell
.\mvnw.cmd exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer" "-Dexec.args=8080"
```

**Alternatively, run the assembled JAR with runtime dependencies on the classpath** (after `.\mvnw.cmd package`, dependencies are under `target/dependency/`):

```powershell
java -cp "target\distrisync-0.1.0-SNAPSHOT.jar;target\dependency\*" com.distrisync.server.WhiteboardServer
```

Expected console output on successful bind:

```
INFO  NioServer      - Server listening on port 9090
INFO  WalManager     - WalManager initialised  dataDir='...\distrisync-data'
```

On restart with an existing WAL:

```
INFO  RoomContext     - Replaying 42 WAL record(s) for roomId='default'
INFO  RoomContext     - WAL replay complete  roomId='default' applied=42 total=42 shapesAfterReplay=38
```

Traffic metrics heartbeat (every 10 seconds):

```
INFO  NioServer      - [METRICS] Traffic routed: 1048576 bytes | Active Rooms: 3 | Active Sockets: 12.
```

---

### 3. Start a Client

Each client instance is an independent JavaFX process. Launch as many as needed; all instances connecting to the same server address will share the canvas in real time. If the backend is running via **Docker Compose**, use the default **localhost** and **9090** (both TCP and UDP must be reachable from the client host for full voice and relay behaviour).

**Connect to localhost (default host/port):**

```powershell
.\mvnw.cmd javafx:run
```

**Connect to a specific host and port:**

```powershell
.\mvnw.cmd javafx:run "-Djavafx.args=192.168.1.100 9090"
```

> Open multiple terminals and run `.\mvnw.cmd javafx:run` in each to simulate multiple collaborating peers locally.

---

### 4. Run Tests Only

```powershell
.\mvnw.cmd test
```

---

## License

This project is released under the MIT License.