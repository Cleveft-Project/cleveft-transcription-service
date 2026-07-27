package com.cleveft.transcriptionservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Semantic search over a student's own lectures.
 *
 * <p>Callers send plain text, not a vector. This service owns the embedding
 * model precisely so that query vectors and stored vectors can never drift into
 * different spaces.
 */
public record SearchRequestDTO(

        @NotBlank(message = "A search question is required")
        String question,

        /** Restrict to a single lecture; null searches the whole corpus. */
        UUID lectureId,

        @Min(value = 1, message = "topK must be at least 1")
        @Max(value = 20, message = "topK may not exceed 20")
        Integer topK
) {

    public int effectiveTopK() {
        return topK == null ? 5 : topK;
    }
}
