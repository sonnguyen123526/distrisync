package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Server-side codec for polymorphic {@link Shape} serialization.
 *
 * <h2>Wire envelope</h2>
 * Every shape is wrapped in a JSON object that carries a {@code "_type"}
 * discriminator alongside all shape fields:
 * <pre>
 * { "_type": "Circle", "objectId": "...", "timestamp": 123, "color": "#FFF",
 *   "x": 10.0, "y": 20.0, "radius": 5.0, "filled": false, "strokeWidth": 1.0 }
 * </pre>
 * This avoids any Gson RuntimeTypeAdapterFactory dependency while remaining
 * fully round-trip safe across the sealed {@code Shape} hierarchy.
 *
 * <h2>SNAPSHOT payload</h2>
 * A JSON array of envelopes — one per shape currently in the canvas.
 *
 * <h2>MUTATION payload</h2>
 * A single envelope JSON object.
 */
final class ShapeCodec {

    /** Discriminator field name injected into every envelope. */
    private static final String TYPE_FIELD = "_type";

    /**
     * Dedicated Gson instance with a UUID TypeAdapter that serialises to/from
     * the standard {@code "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"} string form.
     * Gson's default reflection-based UUID handling produces
     * {@code {"mostSigBits":…,"leastSigBits":…}}, which is neither readable nor
     * compatible with Java's {@link UUID#fromString}.
     */
    static final Gson GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(UUID.class, new UuidAdapter())
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private ShapeCodec() {}

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Serialises a {@link Shape} to a {@link JsonObject} envelope.
     * The concrete runtime type is recorded in {@value #TYPE_FIELD}.
     */
    static JsonObject toEnvelope(Shape shape) {
        JsonObject obj = GSON.toJsonTree(shape).getAsJsonObject();
        obj.addProperty(TYPE_FIELD, shape.getClass().getSimpleName());
        return obj;
    }

    /**
     * Serialises a collection of shapes to a JSON array of envelopes.
     * Used as the payload of a {@code SNAPSHOT} message.
     */
    static String encodeSnapshot(Collection<Shape> shapes) {
        JsonArray array = new JsonArray(shapes.size());
        for (Shape s : shapes) {
            array.add(toEnvelope(s));
        }
        return GSON.toJson(array);
    }

    /**
     * Serialises a single shape to an envelope JSON string.
     * Used as the payload of a {@code MUTATION} message.
     */
    static String encodeMutation(Shape shape) {
        return GSON.toJson(toEnvelope(shape));
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Deserialises a shape from a {@code MUTATION} payload string.
     *
     * @throws IllegalArgumentException if the {@value #TYPE_FIELD} discriminator
     *                                  is absent or refers to an unknown subtype
     */
    static Shape decodeMutation(String payload) {
        JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
        return fromEnvelope(obj);
    }

    /**
     * Deserialises all shapes from a {@code SNAPSHOT} payload string.
     */
    static List<Shape> decodeSnapshot(String payload) {
        JsonArray array = JsonParser.parseString(payload).getAsJsonArray();
        List<Shape> shapes = new ArrayList<>(array.size());
        for (JsonElement el : array) {
            shapes.add(fromEnvelope(el.getAsJsonObject()));
        }
        return shapes;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static Shape fromEnvelope(JsonObject envelope) {
        JsonElement typeEl = envelope.get(TYPE_FIELD);
        if (typeEl == null) {
            throw new IllegalArgumentException("Shape envelope missing '" + TYPE_FIELD + "' field");
        }
        return switch (typeEl.getAsString()) {
            case "Line"        -> GSON.fromJson(envelope, Line.class);
            case "Circle"      -> GSON.fromJson(envelope, Circle.class);
            case "TextNode"    -> GSON.fromJson(envelope, TextNode.class);
            case "EraserPath"  -> GSON.fromJson(envelope, EraserPath.class);
            default -> throw new IllegalArgumentException(
                    "Unknown shape type discriminator: " + typeEl.getAsString());
        };
    }

    // -------------------------------------------------------------------------
    // UUID adapter
    // -------------------------------------------------------------------------

    private static final class UuidAdapter
            implements JsonSerializer<UUID>, JsonDeserializer<UUID> {

        @Override
        public JsonElement serialize(UUID src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public UUID deserialize(JsonElement json, Type type, JsonDeserializationContext ctx)
                throws JsonParseException {
            try {
                return UUID.fromString(json.getAsString());
            } catch (IllegalArgumentException e) {
                throw new JsonParseException("Invalid UUID: " + json.getAsString(), e);
            }
        }
    }
}
