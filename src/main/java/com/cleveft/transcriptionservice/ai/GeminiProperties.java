package com.cleveft.transcriptionservice.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Google GenAI configuration for speech-to-text and embeddings.
 *
 * @param apiKey         Google GenAI API key. Without it the service starts but
 *                       every transcription fails with a clear message rather
 *                       than silently producing placeholder text.
 * @param baseUrl        API root; overridable so tests can point at a stub.
 * @param sttModel       model used to turn lecture audio into text
 * @param embeddingModel model used to vectorise transcript chunks
 * @param dimensions     embedding width — must equal the vector(n) column width
 * @param inlineLimitBytes largest payload sent inline; anything bigger goes
 *                       through the resumable Files API instead
 */
@ConfigurationProperties(prefix = "cleveft.gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String sttModel,
        /**
         * Model used to turn a transcript into structured notes.
         *
         * Separate from {@link #sttModel} because each Gemini model carries its
         * own quota pool, and these two calls are the heaviest things the
         * service does. Sharing one model meant transcribing a lecture and then
         * organising it drew from the same allowance twice — and an imported
         * PDF, which never touches speech-to-text at all, could still be
         * refused because a recording had exhausted the STT model earlier.
         */
        String notesModel,
        /**
         * Model used to read a linked video.
         *
         * Separate for the same reason as {@link #notesModel}: quota is per
         * model, and a student who has spent their speech-to-text allowance on
         * recordings should still be able to import a video.
         */
        String videoModel,
        String embeddingModel,
        int dimensions,
        long inlineLimitBytes,
        Duration requestTimeout
) {

    public GeminiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com";
        }
        if (sttModel == null || sttModel.isBlank()) {
            // gemini-2.5-flash is closed to new API keys and returns 404
            // NOT_FOUND for them, so it cannot be the default.
            sttModel = "gemini-3.5-flash";
        }
        if (notesModel == null || notesModel.isBlank()) {
            notesModel = "gemini-3.5-flash";
        }
        if (videoModel == null || videoModel.isBlank()) {
            videoModel = "gemini-3.5-flash";
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "gemini-embedding-001";
        }
        if (dimensions <= 0) {
            dimensions = 768;
        }
        if (inlineLimitBytes <= 0) {
            // The API caps a whole inline request at 20MB; leave headroom for
            // the base64 expansion and the surrounding JSON.
            inlineLimitBytes = 14L * 1024 * 1024;
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofMinutes(10);
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
