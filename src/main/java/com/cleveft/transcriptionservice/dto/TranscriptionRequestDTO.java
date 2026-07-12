package com.cleveft.transcriptionservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptionRequestDTO {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @NotBlank(message = "Source URL is required")
    private String sourceUrl;

    @Size(max = 10, message = "Language code must not exceed 10 characters")
    private String language;

    private Integer durationSeconds;
}
