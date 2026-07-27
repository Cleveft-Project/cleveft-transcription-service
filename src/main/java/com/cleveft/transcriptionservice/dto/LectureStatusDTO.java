package com.cleveft.transcriptionservice.dto;

import com.cleveft.transcriptionservice.model.Lecture;

import java.util.UUID;

/**
 * Cheap polling target for the client while a lecture is being processed.
 */
public record LectureStatusDTO(
        UUID id,
        Lecture.LectureStatus status,
        String statusDetail,
        int progressPercent,
        boolean terminal
) {

    public static LectureStatusDTO of(Lecture lecture, int totalChunks) {
        Lecture.LectureStatus status = lecture.getStatus();

        int progress = switch (status) {
            case PENDING -> 5;
            case PROCESSING -> lecture.getFullTranscript() == null ? 35 : (totalChunks > 0 ? 85 : 65);
            case COMPLETED -> 100;
            case FAILED -> 100;
        };

        boolean terminal = status == Lecture.LectureStatus.COMPLETED
                || status == Lecture.LectureStatus.FAILED;

        return new LectureStatusDTO(lecture.getId(), status, lecture.getStatusDetail(), progress, terminal);
    }
}
