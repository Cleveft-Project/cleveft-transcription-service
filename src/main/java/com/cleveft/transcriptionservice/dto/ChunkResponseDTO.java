package com.cleveft.transcriptionservice.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkResponseDTO {

    private UUID id;

    private UUID lectureId;

    private Integer chunkIndex;

    private String content;

    private Double startTime;

    private Double endTime;

    private LocalDateTime createdAt;
}
