package com.helios.testforge.persistence;

import org.postgresql.util.PGobject;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;

/**
 * Reads and writes the JSONB payloads the control plane stores.
 *
 * <p>Requests and plans are stored as documents rather than shredded into
 * columns because nothing queries inside them — they are written once and read
 * back whole, to reproduce a dataset or to show a plan in the console. Shredding
 * them would mean a migration every time a plan gains a field.
 */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Serialises to a {@code jsonb} parameter. */
    public PGobject toJsonb(Object value) {
        try {
            PGobject json = new PGobject();
            json.setType("jsonb");
            json.setValue(value == null ? null : mapper.writeValueAsString(value));
            return json;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to build a jsonb parameter", e);
        }
    }

    /** Deserialises a column value, tolerating null. */
    public <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return mapper.readValue(json, type);
    }

    public String toJsonString(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }
}
