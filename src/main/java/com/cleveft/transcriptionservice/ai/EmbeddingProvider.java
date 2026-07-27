package com.cleveft.transcriptionservice.ai;

import java.util.List;

/**
 * Vectorisation boundary.
 *
 * <p>This service owns the vector column, so it also owns embedding — both when
 * indexing a transcript and when answering a search. Keeping both sides on one
 * model is the only way to guarantee query and document vectors live in the
 * same space.
 */
public interface EmbeddingProvider {

    /** Embeds transcript chunks for storage. */
    List<float[]> embedDocuments(List<String> chunks);

    /** Embeds a student's question for retrieval. */
    float[] embedQuery(String question);

    int dimensions();
}
