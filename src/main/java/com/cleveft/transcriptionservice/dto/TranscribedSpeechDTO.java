package com.cleveft.transcriptionservice.dto;

/**
 * What a student just said, as words.
 *
 * <p>An object rather than a bare string so the response can grow — a
 * confidence score, or the language actually detected — without every caller
 * having to change how it reads the body.
 *
 * @param text the transcript, trimmed; empty when nothing intelligible was said
 */
public record TranscribedSpeechDTO(String text) {
}
