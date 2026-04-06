# COMP 352 — Interim Project Report

| Field | Detail |
|---|---|
| **Project Title** | DistriSync — Distributed Collaborative Whiteboard |
| **Authors** | Harrison Nguyen & Son Nguyen |
| **Report Date** | April 2, 2026 |
| **Status** | **Core Foundation Completed** |

---

## 1. Introduction & Project Overview

DistriSync is a real-time, multi-user collaborative whiteboard application built entirely in Java — without any third-party real-time framework. Any number of peers can simultaneously draw, annotate, and erase on a shared canvas; every change converges on all connected clients within a single network round-trip.

### Why we did not build a turn-based game

The project requirements permit any network application; the simplest compliant submission would be a turn-based card or board game where each client waits for a server-issued "your turn" token before sending input. We deliberately bypassed that category. A turn-based model serialises concurrency by design and consequently demonstrates only the most basic socket I/O. DistriSync instead implements a **real-time concurrent state machine**: every peer emits events continuously and independently, the server must multiplex N simultaneous byte streams without blocking, and concurrent mutations from different clients must converge to a consistent canvas without coordination. This forces authentic engagement with the core networking problems the course targets — multiplexing, partial-read handling, conflict resolution, and transport-layer trade-offs between TCP and UDP.

---

## 2. Networking Concepts Applied

### 2.1 Java NIO Sockets — `ServerSocketChannel` and `SocketChannel`

The server (`NioServer.java`) runs a **single-threaded, non-blocking NIO event loop** driven by a `java.nio.channels.Selector`. Accepting a connection, reading from multiple clients, and writing back to them are all handled inside one thread with zero blocking calls on the hot path.

**Connection acceptance** (`NioServer.java`, `handleAccept`):
```java
ServerSocketChannel serverChannel = ServerSocketChannel.open();
serverChannel.configureBlocking(false);
serverChannel.bind(new InetSocketAddress(port));
serverChannel.register(selector, SelectionKey.OP_ACCEPT);
```

Each accepted `SocketChannel` is registered on the same selector for `OP_READ`:
```java
SocketChannel clientChannel = serverChannel.accept();
clientChannel.configureBlocking(false);
clientChannel.register(selector, SelectionKey.OP_READ, session);
```

`OP_WRITE` is armed **only** when a partial write stalls on a full TCP send buffer, and is cleared again once the write queue drains — avoiding a busy-spin that would otherwise consume 100 % of one CPU core.

The client (`NetworkClient.java`) symmetrically opens a `SocketChannel` for its outbound TCP connection to the server, with a dedicated `distrisync-read` thread performing blocking reads and a `distrisync-write` thread draining the outgoing mutation queue.

This design supports the required **≥ 4 simultaneous clients** entirely within one server thread; the log confirms this at startup:
```
NioServer listening — port=9090 minConcurrentClients=4
```

---

### 2.2 Nagle's Algorithm Disabled — `StandardSocketOptions.TCP_NODELAY`

TCP's Nagle algorithm coalesces small segments into larger ones before transmission, which improves bulk throughput but introduces up to 200 ms of artificial latency on small writes — fatal for a real-time drawing tool where every mouse-move event must reach peers immediately.

We explicitly disable Nagle on every accepted client socket (`NioServer.java`, line 189):
```java
clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
```

Socket buffers are also enlarged to 64 KiB each to absorb burst traffic without stalling the event loop:
```java
clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);
```

The `NetworkClient` on the client side mirrors this: `TCP_NODELAY` is also set on the outgoing `SocketChannel` so that small `SHAPE_UPDATE` and `TEXT_UPDATE` frames are transmitted immediately rather than being held for ACK consolidation.

---

### 2.3 Custom Application-Layer Binary Protocol (over TCP)

TCP is a stream protocol; it provides no message boundaries. We implement our own **length-prefixed binary framing** in `MessageCodec.java`:

```
┌───────────────┬──────────────────────────┬────────────────────────────┐
│  Byte 0       │  Bytes 1-4               │  Bytes 5 … (5 + length-1)  │
│  MessageType  │  PayloadLength (int32 BE) │  UTF-8 JSON payload        │
│  (1 byte)     │  (4 bytes, big-endian)    │  (variable)                │
└───────────────┴──────────────────────────┴────────────────────────────┘
```

The header is always exactly **5 bytes** (`MessageCodec.HEADER_BYTES = 5`). The payload length is a signed 32-bit big-endian integer, capped at a hard ceiling of **16 MiB** (`MAX_PAYLOAD_BYTES = 16 * 1024 * 1024`) to guard against malformed frames that could trigger runaway allocations.

**Partial-read handling** is explicit: if the `ByteBuffer` does not yet contain a full frame, the decoder rewinds the buffer position to its entry point and throws `PartialMessageException`. The server's read handler leaves unprocessed bytes in the session accumulation buffer via `compact()` and retries on the next selector wake-up. This correctly handles TCP segmentation at any arbitrary byte boundary.

The protocol defines **11 message types** (wire codes `0x01`–`0x0B`):

| Wire Code | `MessageType` | Purpose |
|---|---|---|
| `0x01` | `HANDSHAKE` | Client identification (`clientId`, `authorName`) |
| `0x02` | `SNAPSHOT` | Full canvas state delivered on initial connect / reconnect |
| `0x03` | `MUTATION` | Committed shape add/update; persisted + broadcast |
| `0x04` | `UDP_POINTER` | Ephemeral cursor position (UDP multicast only) |
| `0x05` | `SHAPE_START` | Peer begins a live stroke; relayed, not persisted |
| `0x06` | `SHAPE_UPDATE` | Incremental stroke points; relayed, not persisted |
| `0x07` | `SHAPE_COMMIT` | Peer finalises stroke; peers flush transient layer |
| `0x08` | `CLEAR_USER_SHAPES` | Remove all shapes owned by a specific `clientId` |
| `0x09` | `UNDO_REQUEST` | Delete one shape by UUID |
| `0x0A` | `SHAPE_DELETE` | Server confirms deletion; broadcast to all peers |
| `0x0B` | `TEXT_UPDATE` | Live typing ghost preview; relayed, not persisted |

#### Shape serialisation

The server-side `ShapeCodec` serialises the **sealed `Shape` hierarchy** — `Line`, `Circle`, `TextNode`, and `EraserPath` — to a JSON envelope that carries a `"_type"` discriminator alongside all domain fields. This enables full round-trip polymorphic deserialisation without Gson's `RuntimeTypeAdapterFactory`. Every shape record carries a `UUID objectId`, a **Lamport `timestamp`** for causal ordering, `color`, `strokeWidth`, `authorName`, and `clientId` fields.

#### Concurrent conflict resolution

`CanvasStateManager` is the authoritative canvas store. It uses `ConcurrentHashMap.compute` to apply a strict **last-writer-wins** merge:

```java
shapeMap.compute(incoming.objectId(), (id, existing) -> {
    if (existing == null || incoming.timestamp() > existing.timestamp()) {
        applied[0] = true;
        return incoming;
    }
    return existing;
});
```

An incoming mutation is broadcast to peers only if it is accepted (newer timestamp). Stale re-deliveries and concurrent duplicate mutations are silently no-ops — a property verified by a 100-virtual-thread concurrency test in `CanvasStateManagerTest.java`.

#### UDP Multicast — live cursor presence

In addition to the TCP channel, `UdpPointerTracker` joins the multicast group **`239.255.42.42:9292`** using a `MulticastSocket`. Cursor positions are transmitted at **20 Hz** (one datagram every 50 ms, only when the mouse has actually moved) as a compact pipe-delimited datagram:

```
UDP_POINTER|<clientId>|<x>|<y>|<authorName>
```

A dedicated daemon receive thread blocks on `MulticastSocket.receive` with a 200 ms timeout. Received positions are dispatched to the JavaFX Application Thread via `Platform.runLater`; cursors that have not sent a packet within 500 ms are faded out and removed. If multicast is unavailable, the tracker degrades gracefully and the rest of the application continues normally over TCP.

---

## 3. Demo Readiness

The **4-client minimum requirement** is fully operational. All of the following functions have been implemented, manually tested with four simultaneous JavaFX client instances, and verified by unit tests:

| Capability | Mechanism |
|---|---|
| Live freehand drawing | `SHAPE_START` → `SHAPE_UPDATE` (streamed) → `SHAPE_COMMIT` relayed to all peers |
| Circle drawing | Same streaming protocol; shape committed as a `Circle` record |
| Eraser tool | `EraserPath` (parallel `double[] xs`, `ys` arrays) committed as a white vector stroke; peers redraw in Lamport order |
| Text insertion | `TEXT_UPDATE` frames carry in-progress keystrokes (~50 ms throttle); `Enter` commits a `TextNode` via `MUTATION` |
| Distributed undo | Client sends `UNDO_REQUEST` with target `shapeId`; server calls `deleteShape` and broadcasts `SHAPE_DELETE` |
| Scoped clear | `CLEAR_USER_SHAPES` removes only the requesting client's shapes; other peers' work is untouched |
| New-joiner sync | Server delivers a `SNAPSHOT` of all current shapes immediately on TCP accept |
| Automatic reconnect | `NetworkClient` retries with back-off and replays `HANDSHAKE` → `SNAPSHOT` to restore state |
| Live cursor presence | UDP multicast at 20 Hz; remote peers shown as coloured dot + name badge on transparent overlay |
| Figma-style attribution | In-progress remote strokes display a floating author label at the stroke tip |

---

## 4. Next Steps — Extra Credit Proposal (20 Points)

We propose two extensions that build directly on the existing architecture to demonstrate advanced distributed-systems and networking concepts.

### 4.1 Multi-Tenant Architecture — Room Multiplexing

**Goal:** Partition the server into isolated named rooms so that concurrent sessions operate on fully independent canvas state.

**Implementation plan:**
- Promote `CanvasStateManager` from a singleton to an instance owned by a `Room` object identified by a room code (e.g. `"COMP352-DEMO"`).
- Add a `roomCode` field to the `HANDSHAKE` payload. The server creates or joins the corresponding `Room` on the first message from each client.
- Each `Room` maintains its own `ConcurrentHashMap<UUID, Shape>` and its own set of `SelectionKey` registrations; broadcast logic in `NioServer.broadcastExcept` is scoped to room membership rather than global selector keys.
- The `SNAPSHOT` delivered on join contains only the shapes in that room.
- This is noted as the **"Session Multiplexing (Rooms)"** item in the project roadmap (`README.md`, Future Roadmap section).

**Why it earns extra credit:** It requires understanding session demultiplexing, scoped state management, and the interaction between the NIO selector (which is shared) and per-room state (which is isolated) — a core distributed-systems architectural concern.

### 4.2 Hybrid P2P / Server Approach — UDP Multicast Screen Sharing

**Goal:** Demonstrate mastery of TCP vs UDP trade-offs by adding a **low-framerate screen-sharing overlay** that bypasses the TCP mutation log entirely.

**Implementation plan:**
- Extend the existing `UdpPointerTracker` infrastructure (multicast group `239.255.42.42`, port `9292`) with a second message type that carries a **compressed partial-canvas frame** (e.g. a JPEG-encoded `BufferedImage` of the current canvas at reduced resolution).
- A "Present" mode allows one client to broadcast canvas frames at ~2–5 FPS to all peers on the multicast group; observers render the frame as a background image overlay.
- Because UDP offers no delivery guarantee, missed frames are simply dropped — the next frame is a full canvas snapshot, not a delta, so there is no cumulative corruption. This contrasts deliberately with the TCP mutation log, which guarantees ordered delivery at the cost of head-of-line blocking.
- The two channels — TCP for authoritative vector mutations, UDP for lossy raster preview — operate simultaneously and independently, demonstrating the practical trade-off: TCP for correctness, UDP for low latency at the cost of reliability.
- This is noted as the **"UDP Ephemeral Screen Broadcasting"** item in the project roadmap (`README.md`, Future Roadmap section).

**Why it earns extra credit:** It requires hands-on application of UDP socket programming (`DatagramSocket` / `MulticastSocket`), explicit reasoning about reliability vs latency, and the hybrid P2P pattern where peers communicate directly rather than routing all data through the central server.

---

## 5. Summary

DistriSync is a production-quality networked application that directly applies every major networking concept from the course syllabus: non-blocking NIO multiplexing, transport-layer socket options (`TCP_NODELAY`, `SO_SNDBUF`, `SO_RCVBUF`), a custom binary application-layer protocol with explicit framing and partial-read handling, and a parallel UDP multicast channel for ephemeral data. The core 4-client requirement is met and demo-ready. The proposed extra-credit extensions — room multiplexing and hybrid P2P UDP screen sharing — are a natural evolution of the existing architecture and will push the application into advanced distributed-systems territory.
