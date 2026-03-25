<div align="center">

# DistriSync

**A distributed real-time collaborative whiteboard built on a dual-protocol TCP NIO + UDP multicast architecture.**

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21.0.4-2C8EBB?style=for-the-badge&logo=java&logoColor=white)](https://openjfx.io/)
[![License](https://img.shields.io/badge/License-MIT-22c55e?style=for-the-badge)](LICENSE)
[![Course](https://img.shields.io/badge/COMP_352-Course_Project-7c3aed?style=for-the-badge)](Technical_Report.pdf)

*Authors: Harrison Nguyen · Son Nguyen*

</div>

---

## Project Overview

DistriSync is a multi-client, real-time collaborative whiteboard that lets peers draw and annotate simultaneously on a shared canvas. It uses a **dual-protocol network architecture** to separate concerns between reliable state synchronization and low-latency presence:

| Channel | Transport | Purpose |
|---------|-----------|---------|
| **Authoritative State** | TCP via Java NIO (`Selector`-based, non-blocking) | Shape mutations, full-board snapshots on connect, guaranteed ordered delivery |
| **Ephemeral Presence** | UDP multicast (`239.255.42.42:9292`) | Live ghost-cursor positions broadcast at 50 ms intervals — zero TCP overhead |

All TCP frames use a custom **5-byte binary header** (`1 byte type + 4 byte big-endian length`) followed by a UTF-8 JSON body, keeping the protocol lightweight while remaining human-debuggable. The server's NIO engine multiplexes all client sockets on a **single thread** via a `Selector` loop, with no thread-per-connection overhead.

---

## Core Features

- **Concurrent State Sync** — A single-threaded Java NIO `Selector` loop multiplexes all connected clients. Incoming shape mutations are applied to the server's `ConcurrentHashMap` with **last-writer-wins** timestamp semantics, then immediately broadcast to every other peer.
- **Vector Graphics** — Three first-class shape primitives are supported: `Line` (start/end coordinates, stroke width, colour), `Circle` (centre, radius, fill toggle, stroke width), and `TextNode` (content, font family, size, bold/italic). All are modelled as a sealed `Shape` interface and transmitted as typed JSON envelopes discriminated by a `"_type"` field.
- **Live Ghost Cursors** — `UdpPointerTracker` multicasts each client's pointer position at ≤ 50 ms. Peers render coloured crosshair overlays (colour derived from a hash of the peer's ID) that **fade after 300 ms** and are evicted after **500 ms** of inactivity.
- **Live Drag Previews** — Semi-transparent dashed preview shapes are rendered locally while the mouse button is held, providing instant visual feedback before a mutation is committed to the network.
- **Full-Board Snapshot on Join** — On TCP connect, the server immediately delivers a `SNAPSHOT` frame containing the complete serialized canvas, so late-joining clients are instantly synchronized regardless of prior activity.
- **Graceful Reconnection** — The client TCP layer performs up to **5 reconnect attempts** with a **2 s back-off**, re-sending a `HANDSHAKE` on each successful reconnect to restore session state.

---

## System Architecture

### Connection & Protocol Flow

```mermaid
sequenceDiagram
    participant C1 as Client A (JavaFX)
    participant S  as NioServer (TCP :9090)
    participant C2 as Client B (JavaFX)
    participant MC as UDP Multicast Group<br/>239.255.42.42:9292

    Note over C1,S: TCP Handshake & Snapshot
    C1->>S: TCP CONNECT
    C1->>S: HANDSHAKE [0x01 | len=2 | "{}"]
    S-->>C1: SNAPSHOT  [0x02 | len=N | JSON shape array]

    Note over C1,S,C2: Mutation Propagation
    C1->>S: MUTATION  [0x03 | len=M | {"_type":"Line", ...}]
    S->>S: applyMutation() — last-writer-wins timestamp check
    S-->>C2: MUTATION  [0x03 | len=M | {"_type":"Line", ...}]
    Note right of S: broadcastExcept(sender)

    Note over C1,MC,C2: UDP Cursor Presence (parallel, 50 ms tick)
    loop every 50 ms (only if pointer moved)
        C1-)MC: "UDP_POINTER|a1b2c3d4|412.0|308.5"
        MC-)C2: (same datagram — multicast delivery)
        C2->>C2: renderCursors() — crosshair overlay + fade timer
    end
```

### TCP Frame Format

```
 Byte offset →  0        1        2        3        4        5 … 5+len
                ┌────────┬────────┬────────┬────────┬────────┬──────────────────┐
                │  type  │         payload length (int32 big-endian)  │  payload  │
                │ 1 byte │                  4 bytes                   │ len bytes │
                └────────┴────────┴────────┴────────┴────────┴──────────────────┘

  type values
  ──────────────────────────────────────────────────────────────
  0x01  HANDSHAKE    client greeting (payload: "{}")
  0x02  SNAPSHOT     full board state (payload: JSON shape array)
  0x03  MUTATION     incremental shape update (payload: JSON shape object)
  0x04  UDP_POINTER  cursor presence — transmitted via UDP only; reserved on TCP
```

### UDP Cursor Datagram Format

```
  UDP_POINTER|<clientId>|<x>|<y>
  ─────────────────────────────────────────────────────────────
  clientId  first 8 chars of a random UUID (e.g. "a1b2c3d4")
  x, y      double-precision floating-point canvas coordinates
  encoding  UTF-8 plain text, pipe-delimited
```

---

## Package Structure

```
distrisync/
├── src/
│   ├── main/java/com/distrisync/
│   │   ├── client/
│   │   │   ├── WhiteboardApp.java          # JavaFX Application entry point & render loop
│   │   │   ├── WhiteboardClient.java       # Legacy CLI entry-point (delegates to App)
│   │   │   ├── NetworkClient.java          # Blocking NIO TCP client (read + write daemon threads)
│   │   │   ├── CanvasUpdateListener.java   # Callback interface for inbound mutations
│   │   │   ├── UdpPointerTracker.java      # UDP multicast cursor send / receive / render
│   │   │   ├── PointerStateManager.java    # In-memory cursor state registry + eviction
│   │   │   └── PointerState.java           # Immutable cursor snapshot (id, x, y, timestamp)
│   │   ├── server/
│   │   │   ├── WhiteboardServer.java       # Server entry point & shutdown-hook lifecycle
│   │   │   ├── NioServer.java              # Selector event loop, accept / read / broadcast
│   │   │   ├── ClientSession.java          # Per-connection read buffer + write queue
│   │   │   ├── CanvasStateManager.java     # Authoritative shape map, LWW merge logic
│   │   │   └── ShapeCodec.java             # Server-side Gson shape serialization
│   │   ├── protocol/
│   │   │   ├── MessageType.java            # Wire-byte opcodes (0x01 – 0x04)
│   │   │   ├── Message.java                # Immutable message record (type + JSON payload)
│   │   │   ├── MessageCodec.java           # 5-byte framing encode / decode (HEADER_BYTES = 5)
│   │   │   └── PartialMessageException.java
│   │   └── model/
│   │       ├── Shape.java                  # Sealed interface — permits Line, Circle, TextNode
│   │       ├── Line.java
│   │       ├── Circle.java
│   │       └── TextNode.java
│   └── test/java/com/distrisync/
│       ├── integration/ClientServerIntegrationTest.java
│       ├── client/PointerStateTrackerTest.java
│       ├── server/CanvasStateManagerTest.java
│       └── protocol/MessageCodecTest.java
└── pom.xml
```

---

## Prerequisites

| Requirement | Minimum Version | Verify with |
|-------------|-----------------|-------------|
| JDK | **21** | `java -version` |
| Apache Maven | **3.8+** | `mvn -version` |

> **Windows path check:** Ensure `JAVA_HOME` points to your JDK 21 installation and `%JAVA_HOME%\bin` appears on your `PATH` before running any commands below.

---

## Build

Compile sources and package the artifact (skipping tests for a fast build):

```powershell
mvn clean install -DskipTests
```

To compile **and** run the full test suite (unit + integration):

```powershell
mvn clean verify
```

---

## Running the Application

### Step 1 — Start the Server

The NIO server binds to **TCP port `9090`** by default. An optional positional argument selects a different port.

```powershell
# Default port (9090)
mvn exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer"

# Custom port example
mvn exec:java "-Dexec.mainClass=com.distrisync.server.WhiteboardServer" "-Dexec.args=8080"
```

A successful start prints a line similar to:

```
INFO  NioServer - Listening on port 9090
```

Leave this terminal running for the lifetime of the session.

---

### Step 2 — Start the Client(s)

> **Two or more client instances are required to observe real-time collaboration.** Open a separate PowerShell window for each client.

**Connect to `localhost` on the default port:**

```powershell
mvn javafx:run
```

**Connect to a specific host and port:**

```powershell
mvn javafx:run "-Djavafx.args=192.168.1.10 9090"
```

Repeat the above command in a second (and third, etc.) terminal window. Each client window receives an immediate full-board snapshot and will display remote peers' ghost cursors as they move their mouse.

---

## Course Documentation

> Detailed architectural decisions, custom binary protocol byte-structure rationale, concurrency model analysis, conflict-resolution strategy, and generative AI usage reflections are documented in the accompanying formal report:
>
> **[`Technical_Report.pdf`](Technical_Report.pdf)**
>
> This document satisfies the COMP 352 written deliverable requirement and should be read alongside the source code for full context on design trade-offs and protocol specification.

---

<div align="center">

*COMP 352 — Distributed Systems · Harrison Nguyen & Son Nguyen*

</div>
