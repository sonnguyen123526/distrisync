# DistriSync — Distributed Collaborative Whiteboard

**Authors:** Harrison Nguyen & Son Nguyen

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-21.0.4-blue?logo=java&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.x-C71A36?logo=apachemaven&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Project Overview

DistriSync is a peer-class distributed collaborative whiteboard built entirely on a custom Java NIO server — no third-party real-time framework. The server runs a single-threaded `java.nio.channels.Selector` event loop that accepts connections, multiplexes reads and writes, and fans out shape mutations to all connected clients with zero blocking I/O on the hot path. Accepted `SocketChannel` instances are configured with `TCP_NODELAY = true` and 64 KiB socket buffers to minimise Nagle-induced latency, enabling sub-frame delivery of stroke data across a local network. A length-prefixed binary frame (1-byte type tag + 4-byte big-endian payload length + UTF-8 JSON body) with a 16 MiB hard ceiling governs every exchange between server and clients, providing deterministic parse complexity regardless of payload size.

The client UI is built on JavaFX 21 and styled with a Tailwind-inspired `styles.css` design system — a flat, neutral-toned control panel with uniform spacing and hover states. A layered canvas architecture (base paint layer → remote transient layer → local transient layer → cursor overlay → control pane) ensures that in-progress strokes from remote peers are rendered live without flickering or compositing artefacts. A parallel UDP multicast channel (`239.255.42.42:9292`) carries ephemeral pointer positions at 20 Hz, giving all peers real-time cursor presence without touching the TCP state machine. Shared canvas authority is implemented via last-writer-wins using Lamport-ish per-shape timestamps enforced atomically inside a `ConcurrentHashMap`, so concurrent edits converge without a central lock. Room-scoped state is durably persisted to a per-room Write-Ahead Log (WAL) so that a server restart reconstructs every room's canvas from the on-disk record before the first reconnecting client joins.

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

- **Session Multiplexing (Rooms)** — `RoomManager` maintains a `ConcurrentHashMap<String, RoomContext>` of isolated rooms, each owning a dedicated `CanvasStateManager` and a `ConcurrentHashMap`-backed `Set<SelectionKey>`. Rooms are created lazily on first client join and become quiescent (not garbage-collected) when their last client disconnects, so rejoining clients always receive the canvas they left. `RoomContext` scopes all broadcasts so that mutations in one room are never visible to clients in another.

- **Write-Ahead Log (WAL) with Crash Recovery** — `WalManager` appends every accepted state-mutating frame (`MUTATION`, `SHAPE_DELETE`, `CLEAR_USER_SHAPES`) to a per-room `{roomId}.wal` file under `distrisync-data/` using `FileChannel` in `APPEND` mode. On server restart, `RoomContext` calls `WalManager.recover(roomId)` before any client joins: frames are decoded sequentially from a heap `ByteBuffer`; a truncated tail frame — the typical artefact of a mid-write crash — is silently tolerated and recovery stops at the corrupt offset, returning the clean prefix. Room identifiers are sanitised (`[^a-zA-Z0-9_\-]` → `_`) before use as filenames to prevent path traversal.

- **Last-Writer-Wins Convergence** — `CanvasStateManager` uses `ConcurrentHashMap.compute` with a strictly-greater-timestamp guard, so concurrent mutations from multiple peers converge without server-side locking or operational transforms.

- **Reconnect with Back-off** — `NetworkClient` reconnects automatically after EOF or I/O errors using a synchronised `reconnect()` cycle that re-executes the full `HANDSHAKE` → `SNAPSHOT` flow to restore canvas state.

---

## Project Structure

```
distrisync/
├── src/
│   ├── main/
│   │   ├── java/com/distrisync/
│   │   │   ├── client/              # JavaFX UI, TCP client, UDP pointer tracker
│   │   │   │   ├── WhiteboardApp.java
│   │   │   │   ├── NetworkClient.java
│   │   │   │   ├── UdpPointerTracker.java
│   │   │   │   ├── CanvasUpdateListener.java
│   │   │   │   ├── PointerState.java
│   │   │   │   └── PointerStateManager.java
│   │   │   ├── server/              # NIO server, room routing, WAL, canvas authority
│   │   │   │   ├── WhiteboardServer.java
│   │   │   │   ├── NioServer.java
│   │   │   │   ├── RoomManager.java
│   │   │   │   ├── RoomContext.java
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
│           ├── client/PointerStateTrackerTest.java
│           ├── integration/ClientServerIntegrationTest.java
│           ├── protocol/MessageCodecTest.java
│           └── server/
│               ├── CanvasStateManagerTest.java
│               ├── RoomManagerTest.java
│               └── WalManagerTest.java
├── docs/
│   └── Architecture.md
└── pom.xml
```

---

## Wire Protocol Reference

| `MessageType` | Byte | Direction | Persisted to WAL | Description |
|---|---|---|---|---|
| `HANDSHAKE` | `0x01` | C → S | No | Client identification (`clientId`, `authorName`) |
| `SNAPSHOT` | `0x02` | S → C | No | Full canvas state on connect / reconnect |
| `MUTATION` | `0x03` | C ↔ S | **Yes** | Committed shape add/update; broadcast to room peers |
| `UDP_POINTER` | `0x04` | UDP only | No | Ephemeral cursor position (multicast, not TCP) |
| `SHAPE_START` | `0x05` | C ↔ S | No | Begin live stroke; relayed, not persisted |
| `SHAPE_UPDATE` | `0x06` | C ↔ S | No | Incremental stroke points; relayed, not persisted |
| `SHAPE_COMMIT` | `0x07` | C ↔ S | No | Finalise live stroke; peer dismisses transient layer |
| `CLEAR_USER_SHAPES` | `0x08` | C ↔ S | **Yes** | Remove all shapes owned by a specific `clientId` |
| `UNDO_REQUEST` | `0x09` | C → S | No | Request last-shape deletion |
| `SHAPE_DELETE` | `0x0A` | S → C | **Yes** | Broadcast shape removal after undo |
| `TEXT_UPDATE` | `0x0B` | C ↔ S | No | Live text ghost preview; relayed, not persisted |

---

## Prerequisites

| Requirement | Minimum Version |
|---|---|
| JDK | 21 |
| Apache Maven | 3.8+ |
| Network | Server and clients on the same subnet (UDP multicast) |

> **Note:** Ensure `JAVA_HOME` points to a JDK 21 installation and `mvn` is on your `PATH` before proceeding.

---

## Build & Execution

### 1. Build the Project

Run once from the repository root to compile sources and execute all unit tests:

```powershell
mvn clean install
```

---

### 2. Start the Server

The server binds on TCP port **9090** by default. An optional positional argument overrides the port. WAL files are written to `distrisync-data/` in the working directory and created automatically on first run.

**Default port (9090):**

```powershell
mvn exec:java -Dexec.mainClass=com.distrisync.server.WhiteboardServer
```

**Custom port (e.g., 8080):**

```powershell
mvn exec:java -Dexec.mainClass=com.distrisync.server.WhiteboardServer -Dexec.args="8080"
```

**Alternatively, run the assembled JAR directly:**

```powershell
java -cp target/distrisync-0.1.0-SNAPSHOT.jar com.distrisync.server.WhiteboardServer
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

---

### 3. Start a Client

Each client instance is an independent JavaFX process. Launch as many as needed; all instances connecting to the same server address will share the canvas in real time.

**Connect to localhost (default host/port):**

```powershell
mvn javafx:run
```

**Connect to a specific host and port:**

```powershell
mvn javafx:run "-Djavafx.args=192.168.1.100 9090"
```

> Open multiple terminals and run `mvn javafx:run` in each to simulate multiple collaborating peers locally.

---

### 4. Run Tests Only

```powershell
mvn test
```

---

## License

This project is released under the [MIT License](LICENSE).
