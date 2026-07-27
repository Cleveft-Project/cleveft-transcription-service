package com.cleveft.transcriptionservice.dto;

import com.cleveft.transcriptionservice.model.Lecture;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full lecture detail, including transcript, notes and chunks.
 */
public record LectureResponseDTO(
        UUID id,
        String title,
        String courseCode,
        String language,
        Integer durationSeconds,
        Lecture.LectureStatus status,
        String statusDetail,
        /** Recording, imported PDF or YouTube. */
        Lecture.LectureSource source,
        String sourceUrl,
        String fullTranscript,
        List<Map<String, Object>> structuredNotes,
        List<Map<String, Object>> keyConcepts,
        int totalChunks,
        List<ChunkResponseDTO> chunks,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static LectureResponseDTO of(Lecture lecture, List<ChunkResponseDTO> chunks, int totalChunks) {
        return new LectureResponseDTO(
                lecture.getId(),
                lecture.getTitle(),
                lecture.getCourseCode(),
                lecture.getLanguage(),
                lecture.getDurationSeconds(),
                lecture.getStatus(),
                lecture.getStatusDetail(),
                lecture.getSource(),
                lecture.getSourceUrl(),
                lecture.getFullTranscript(),
                lecture.getStructuredNotes(),
                lecture.getKeyConcepts(),
                totalChunks,
                chunks,
                lecture.getCreatedAt(),
                lecture.getUpdatedAt());
    }
}
