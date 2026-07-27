package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.dto.ChunkMatchDTO;
import com.cleveft.transcriptionservice.dto.LectureResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureStatusDTO;
import com.cleveft.transcriptionservice.dto.LectureSummaryDTO;
import com.cleveft.transcriptionservice.dto.SearchRequestDTO;
import com.cleveft.transcriptionservice.dto.UpdateLectureRequestDTO;
import com.cleveft.transcriptionservice.dto.UsageDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface TranscriptionService {

    /**
     * Accepts a recording and queues it for transcription.
     *
     * @return the lecture in {@code PENDING}; processing continues in the
     * background and the client polls {@link #getStatus}
     */
    LectureResponseDTO submitRecording(UUID userId,
                                       MultipartFile audio,
                                       String title,
                                       String courseCode,
                                       String language,
                                       Integer durationSeconds);

    /**
     * Accepts a PDF and queues it for import.
     *
     * <p>Behaves exactly like {@link #submitRecording} from the client's point
     * of view — same 202, same status polling, same resulting lecture. Only the
     * first stage of the pipeline differs: text is extracted from the document
     * rather than transcribed from audio.
     *
     * @return the lecture in {@code PENDING}
     */
    LectureResponseDTO submitDocument(UUID userId,
                                      MultipartFile document,
                                      String title,
                                      String courseCode);

    List<LectureSummaryDTO> listLectures(UUID userId);

    LectureResponseDTO getLecture(UUID userId, UUID lectureId);

    LectureStatusDTO getStatus(UUID userId, UUID lectureId);

    LectureResponseDTO updateLecture(UUID userId, UUID lectureId, UpdateLectureRequestDTO request);

    /**
     * Re-runs the pipeline for a lecture using the audio retained from the
     * original upload — no re-recording required.
     *
     * @throws com.cleveft.transcriptionservice.exception.ApiException if the
     * lecture is already processing, or its audio was not retained (audio
     * retention disabled, or the file has since been lost)
     */
    LectureResponseDTO retryProcessing(UUID userId, UUID lectureId);

    void deleteLecture(UUID userId, UUID lectureId);

    /**
     * Semantic search across the student's own lectures. Takes plain text — this
     * service owns the embedding model so query and document vectors always come
     * from the same place.
     */
    List<ChunkMatchDTO> search(UUID userId, SearchRequestDTO request);

    /** Dashboard counters. */
    LibraryStats stats(UUID userId);

    /**
     * This period's recording usage against the caller's tier allowance.
     *
     * <p>Lives here rather than on the auth service because the count comes from
     * the lectures this service owns; auth supplies only the allowance.
     */
    UsageDTO usage(UUID userId);

    record LibraryStats(long totalLectures, long completedLectures, long processingLectures, long totalChunks) {
    }
}
