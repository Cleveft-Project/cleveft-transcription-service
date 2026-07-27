package com.cleveft.transcriptionservice.dto;

import com.cleveft.transcriptionservice.repository.LectureChunkRepository.ChunkMatch;

import java.util.UUID;

/**
 * A retrieval hit. Carries everything the query service needs to build a
 * citation without calling back for lecture metadata.
 */
public record ChunkMatchDTO(
        UUID chunkId,
        UUID lectureId,
        String lectureTitle,
        Integer chunkIndex,
        String content,
        Double startTime,
        Double endTime,
        String topicTag,
        Double similarity
) {

    public static ChunkMatchDTO from(ChunkMatch match) {
        return new ChunkMatchDTO(
                match.getId(),
                match.getLectureId(),
                match.getLectureTitle(),
                match.getChunkIndex(),
                match.getContent(),
                match.getStartTime(),
                match.getEndTime(),
                match.getTopicTag(),
                match.getSimilarity());
    }
}
