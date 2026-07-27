package com.cleveft.transcriptionservice.dto;

import com.cleveft.transcriptionservice.model.Lecture;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * List-view projection. Deliberately omits the transcript and chunks — a
 * dashboard showing twenty lectures should not ship twenty full transcripts.
 */
public record LectureSummaryDTO(
        UUID id,
        String title,
        String courseCode,
        Integer durationSeconds,
        Lecture.LectureStatus status,
        String statusDetail,
        /** Recording, imported PDF or YouTube — shown as a badge on the card. */
        Lecture.LectureSource source,
        int totalChunks,
        /** Key-concept terms, for display on the lecture card. */
        List<String> topics,
        /**
         * Canonical topic tags taken from this lecture's chunks. Shares a
         * vocabulary with exam-prep mastery tracking, so it is what any
         * "have I covered this?" comparison must use.
         */
        List<String> topicTags,
        String preview,
        OffsetDateTime createdAt
) {

    private static final int PREVIEW_CHARS = 180;

    public static LectureSummaryDTO of(Lecture lecture, int totalChunks, List<String> topicTags) {
        return new LectureSummaryDTO(
                lecture.getId(),
                lecture.getTitle(),
                lecture.getCourseCode(),
                lecture.getDurationSeconds(),
                lecture.getStatus(),
                lecture.getStatusDetail(),
                lecture.getSource(),
                totalChunks,
                topicsOf(lecture),
                topicTags == null ? List.of() : topicTags,
                previewOf(lecture.getFullTranscript()),
                lecture.getCreatedAt());
    }

    private static List<String> topicsOf(Lecture lecture) {
        List<Map<String, Object>> concepts = lecture.getKeyConcepts();
        if (concepts == null) {
            return List.of();
        }
        return concepts.stream()
                .map(concept -> String.valueOf(concept.get("term")))
                .filter(term -> !term.isBlank() && !"null".equals(term))
                .limit(4)
                .toList();
    }

    private static String previewOf(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return null;
        }
        String flattened = transcript.strip().replaceAll("\\s+", " ");
        return flattened.length() <= PREVIEW_CHARS
                ? flattened
                : flattened.substring(0, PREVIEW_CHARS).trim() + "…";
    }
}
