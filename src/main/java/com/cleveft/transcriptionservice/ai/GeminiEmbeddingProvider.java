package com.cleveft.transcriptionservice.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    /**
     * The batch endpoint rejects oversized payloads, and a long lecture can
     * produce hundreds of chunks, so requests are split.
     */
    private static final int BATCH_SIZE = 50;

    private final GeminiClient client;
    private final GeminiProperties properties;

    public GeminiEmbeddingProvider(GeminiClient client, GeminiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<float[]> embedDocuments(List<String> chunks) {
        List<float[]> all = new ArrayList<>(chunks.size());

        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            List<String> batch = chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size()));
            all.addAll(client.embed(batch, "RETRIEVAL_DOCUMENT"));
        }
        return all;
    }

    @Override
    public float[] embedQuery(String question) {
        List<float[]> result = client.embed(List.of(question), "RETRIEVAL_QUERY");
        if (result.isEmpty()) {
            throw new AiServiceException("Could not embed the question.");
        }
        return result.get(0);
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }
}
