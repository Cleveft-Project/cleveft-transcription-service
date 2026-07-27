package com.cleveft.transcriptionservice.ai;

/**
 * Speech-to-text boundary.
 *
 * <p>The rest of the service depends on this interface only, so swapping Gemini
 * for Whisper (or a self-hosted model) is a matter of adding one bean — see
 * {@code cleveft.stt.provider} in application.yml.
 */
public interface SttProvider {

    /**
     * @param audio    raw audio bytes as uploaded by the client
     * @param mimeType resolved audio MIME type, e.g. {@code audio/mp4}
     * @param language BCP-47 hint such as {@code en}; may be null
     * @return the verbatim transcript
     * @throws AiServiceException if the provider cannot produce a transcript
     */
    String transcribe(byte[] audio, String mimeType, String language);

    /**
     * Identifier reported on the lecture record so a transcript can always be
     * traced back to the engine that produced it.
     */
    String name();
}
