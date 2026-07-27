package com.cleveft.transcriptionservice.dto;

import jakarta.validation.constraints.Size;

/**
 * Edits from the transcript screen.
 *
 * <p>Supplying {@code fullTranscript} re-runs chunking, embedding and note
 * structuring — a corrected transcript that still retrieves against the old
 * vectors would be worse than no correction at all.
 */
public record UpdateLectureRequestDTO(

        @Size(max = 500, message = "Title is too long")
        String title,

        @Size(max = 255)
        String courseCode,

        String fullTranscript
) {

    public boolean hasTranscriptEdit() {
        return fullTranscript != null && !fullTranscript.isBlank();
    }
}
