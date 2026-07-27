package com.cleveft.transcriptionservice.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Writes the pgvector column.
 *
 * <p>Kept out of the JPA entity on purpose — see {@code LectureChunk}. Vectors
 * are sent as their textual {@code [a,b,c]} form and cast server-side, which is
 * the one representation every pgvector version accepts from the JDBC driver
 * without a custom type.
 */
@Component
public class ChunkVectorWriter {

    private final JdbcTemplate jdbcTemplate;

    public ChunkVectorWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(UUID chunkId, float[] embedding) {
        jdbcTemplate.update(
                "UPDATE transcription.chunks SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(embedding), chunkId);
    }

    /**
     * One round trip for a whole lecture instead of one per chunk.
     */
    public void writeAll(List<UUID> chunkIds, List<float[]> embeddings) {
        if (chunkIds.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "Got " + embeddings.size() + " embeddings for " + chunkIds.size() + " chunks");
        }

        jdbcTemplate.batchUpdate(
                "UPDATE transcription.chunks SET embedding = CAST(? AS vector) WHERE id = ?",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setString(1, toVectorLiteral(embeddings.get(i)));
                        ps.setObject(2, chunkIds.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunkIds.size();
                    }
                });
    }

    /**
     * pgvector's text format: {@code [0.1,0.2,0.3]}, no spaces.
     */
    public static String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Embedding must not be empty");
        }

        StringBuilder builder = new StringBuilder(embedding.length * 12 + 2);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }
}
