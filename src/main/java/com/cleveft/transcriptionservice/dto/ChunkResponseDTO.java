package com.cleveft.transcriptionservice.dto;

import com.cleveft.transcriptionservice.model.LectureChunk;

import java.util.UUID;

public record ChunkResponseDTO(
        UUID id,
        UUID lectureId,
        Integer chunkIndex,
        String content,
        Double startTime,
        Double endTime,
        String topicTag
) {

    public static ChunkResponseDTO from(LectureChunk chunk) {
        return new ChunkResponseDTO(
                chunk.getId(),
                chunk.getLecture().getId(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                chunk.getStartTime(),
                chunk.getEndTime(),
                chunk.getTopicTag());
    }
}
