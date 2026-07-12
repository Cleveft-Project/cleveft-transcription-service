package com.cleveft.transcriptionservice.controller;

import com.cleveft.transcriptionservice.dto.ChunkResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureResponseDTO;
import com.cleveft.transcriptionservice.dto.TranscriptionRequestDTO;
import com.cleveft.transcriptionservice.service.TranscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transcriptions")
@RequiredArgsConstructor
@Slf4j
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    // ----------------------------------------------------------------
    //  POST / — kick off lecture processing
    // ----------------------------------------------------------------

    @PostMapping
    public ResponseEntity<LectureResponseDTO> createTranscription(
            @Valid @RequestBody TranscriptionRequestDTO request) {

        log.info("POST /api/v1/transcriptions — title: {}", request.getTitle());
        LectureResponseDTO response = transcriptionService.processLecture(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ----------------------------------------------------------------
    //  GET / — list all lectures
    // ----------------------------------------------------------------

    @GetMapping
    public ResponseEntity<List<LectureResponseDTO>> getAllLectures() {

        log.info("GET /api/v1/transcriptions");
        List<LectureResponseDTO> lectures = transcriptionService.getAllLectures();
        return ResponseEntity.ok(lectures);
    }

    // ----------------------------------------------------------------
    //  GET /{lectureId} — fetch full lecture details
    // ----------------------------------------------------------------

    @GetMapping("/{lectureId}")
    public ResponseEntity<LectureResponseDTO> getLecture(
            @PathVariable UUID lectureId) {

        log.info("GET /api/v1/transcriptions/{}", lectureId);
        LectureResponseDTO response = transcriptionService.getLectureById(lectureId);
        return ResponseEntity.ok(response);
    }

    // ----------------------------------------------------------------
    //  GET /search — vector similarity search on chunks
    // ----------------------------------------------------------------

    /**
     * Searches for the most similar chunks via cosine distance.
     *
     * <p>Query parameters:</p>
     * <ul>
     *   <li>{@code embedding} (required) — comma-separated float values, e.g. {@code 0.1,0.2,...}</li>
     *   <li>{@code lectureId} (optional) — scope search to a specific lecture</li>
     *   <li>{@code topK} (optional, default 5) — maximum results to return</li>
     * </ul>
     *
     * <p>Example: {@code GET /api/v1/transcriptions/search?embedding=0.1,0.2,...&topK=10}</p>
     */
    @GetMapping("/search")
    public ResponseEntity<List<ChunkResponseDTO>> searchChunks(
            @RequestParam String embedding,
            @RequestParam(required = false) UUID lectureId,
            @RequestParam(defaultValue = "5") int topK) {

        log.info("GET /api/v1/transcriptions/search — lectureId: {}, topK: {}", lectureId, topK);

        float[] queryEmbedding = parseEmbeddingString(embedding);

        List<ChunkResponseDTO> results;
        if (lectureId != null) {
            results = transcriptionService.searchChunksByLectureAndEmbedding(lectureId, queryEmbedding, topK);
        } else {
            results = transcriptionService.searchChunksByEmbedding(queryEmbedding, topK);
        }

        return ResponseEntity.ok(results);
    }

    // ================================================================
    //  Private helpers
    // ================================================================

    /**
     * Parses a comma-separated string of floats into a float array.
     *
     * @param embeddingStr e.g. "0.1,0.2,0.3"
     * @return the parsed float array
     * @throws IllegalArgumentException if the string is blank or contains non-numeric values
     */
    private float[] parseEmbeddingString(String embeddingStr) {
        if (embeddingStr == null || embeddingStr.isBlank()) {
            throw new IllegalArgumentException("Embedding parameter must not be empty");
        }

        try {
            String[] parts = embeddingStr.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid embedding format. Expected comma-separated floats, e.g. '0.1,0.2,0.3'", e);
        }
    }
}
