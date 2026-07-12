package com.cleveft.transcriptionservice.dto;

import com.cleveft.transcriptionservice.model.Lecture.LectureStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LectureResponseDTO {

    private UUID id;

    private String title;

    private String sourceUrl;

    private String language;

    private Integer durationSeconds;

    private LectureStatus status;

    private String fullTranscript;

    private int totalChunks;

    private List<ChunkResponseDTO> chunks;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
