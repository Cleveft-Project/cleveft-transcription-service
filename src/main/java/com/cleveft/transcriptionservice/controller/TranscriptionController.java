package com.cleveft.transcriptionservice.controller;

import com.cleveft.transcriptionservice.dto.ChunkMatchDTO;
import com.cleveft.transcriptionservice.dto.LectureResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureStatusDTO;
import com.cleveft.transcriptionservice.dto.LectureSummaryDTO;
import com.cleveft.transcriptionservice.dto.SearchRequestDTO;
import com.cleveft.transcriptionservice.dto.UpdateLectureRequestDTO;
import com.cleveft.transcriptionservice.dto.UsageDTO;
import com.cleveft.transcriptionservice.exception.ApiException;
import com.cleveft.transcriptionservice.service.TranscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Lecture capture and retrieval.
 *
 * <p>Every endpoint is scoped to the caller. The user id comes from
 * {@code X-User-Id}, which the gateway sets from a verified token after
 * stripping any client-supplied copy — this service must not be exposed
 * directly to the internet.
 */
@RestController
@RequestMapping("/api/v1/transcriptions")
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    public TranscriptionController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    /**
     * Uploads a recording and queues transcription.
     *
     * <p>Returns 202 Accepted with the lecture in {@code PENDING}. Poll
     * {@code /{id}/status} for progress.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LectureResponseDTO> uploadRecording(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "courseCode", required = false) String courseCode,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds) {

        LectureResponseDTO lecture = transcriptionService.submitRecording(
                requireUserId(userId), file, title, courseCode, language, durationSeconds);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(lecture);
    }

    /**
     * Imports a PDF and queues it for indexing.
     *
     * <p>A separate route rather than a flag on the recording endpoint: the two
     * take different parameters — a document has no duration and no language to
     * transcribe in — and a single endpoint would have to ignore half its inputs
     * depending on what arrived.
     */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LectureResponseDTO> uploadDocument(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "courseCode", required = false) String courseCode) {

        LectureResponseDTO lecture = transcriptionService.submitDocument(
                requireUserId(userId), file, title, courseCode);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(lecture);
    }

    @GetMapping
    public ResponseEntity<List<LectureSummaryDTO>> listLectures(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        return ResponseEntity.ok(transcriptionService.listLectures(requireUserId(userId)));
    }

    @GetMapping("/stats")
    public ResponseEntity<TranscriptionService.LibraryStats> stats(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        return ResponseEntity.ok(transcriptionService.stats(requireUserId(userId)));
    }

    /**
     * Recordings used against the caller's plan allowance this period. Mapped
     * before {@code /{lectureId}} for the same reason as {@code /search}.
     */
    @GetMapping("/usage")
    public ResponseEntity<UsageDTO> usage(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        return ResponseEntity.ok(transcriptionService.usage(requireUserId(userId)));
    }

    /**
     * Semantic search. Mapped before {@code /{lectureId}} would be considered,
     * since "search" is not a UUID and must not be routed there.
     */
    @PostMapping("/search")
    public ResponseEntity<List<ChunkMatchDTO>> search(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody SearchRequestDTO request) {

        return ResponseEntity.ok(transcriptionService.search(requireUserId(userId), request));
    }

    @GetMapping("/{lectureId}")
    public ResponseEntity<LectureResponseDTO> getLecture(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId) {

        return ResponseEntity.ok(transcriptionService.getLecture(requireUserId(userId), lectureId));
    }

    @GetMapping("/{lectureId}/status")
    public ResponseEntity<LectureStatusDTO> getStatus(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId) {

        return ResponseEntity.ok(transcriptionService.getStatus(requireUserId(userId), lectureId));
    }

    @PatchMapping("/{lectureId}")
    public ResponseEntity<LectureResponseDTO> updateLecture(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId,
            @Valid @RequestBody UpdateLectureRequestDTO request) {

        return ResponseEntity.ok(
                transcriptionService.updateLecture(requireUserId(userId), lectureId, request));
    }

    /**
     * Re-runs the pipeline using the retained audio from the original upload.
     * No re-recording, no new upload — just another attempt.
     */
    @PostMapping("/{lectureId}/retry")
    public ResponseEntity<LectureResponseDTO> retry(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId) {

        LectureResponseDTO lecture = transcriptionService.retryProcessing(requireUserId(userId), lectureId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(lecture);
    }

    @DeleteMapping("/{lectureId}")
    public ResponseEntity<Void> deleteLecture(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable UUID lectureId) {

        transcriptionService.deleteLecture(requireUserId(userId), lectureId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.unauthorized("Authentication required.");
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("Malformed identity header.");
        }
    }
}
