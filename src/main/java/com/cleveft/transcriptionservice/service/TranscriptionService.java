package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.dto.ChunkResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureResponseDTO;
import com.cleveft.transcriptionservice.dto.TranscriptionRequestDTO;

import java.util.List;
import java.util.UUID;

/**
 * Core service contract for lecture transcription workflows.
 */
public interface TranscriptionService {

    /**
     * Accepts an audio lecture request, persists the lecture record,
     * and orchestrates transcription processing (chunking + embedding generation).
     *
     * @param request the incoming transcription request metadata
     * @return the created lecture summary with initial status
     */
    LectureResponseDTO processLecture(TranscriptionRequestDTO request);

    /**
     * Retrieves a lecture summary by its unique identifier,
     * including metadata, status, and all associated chunks.
     *
     * @param lectureId the lecture UUID
     * @return the full lecture response with chunks
     */
    LectureResponseDTO getLectureById(UUID lectureId);

    /**
     * Retrieves all lectures, ordered by creation date descending.
     *
     * @return list of lecture summaries (without nested chunks)
     */
    List<LectureResponseDTO> getAllLectures();

    /**
     * Searches for the most relevant timestamped chunks across all lectures
     * using vector similarity (cosine distance) against the provided query embedding.
     *
     * @param queryEmbedding the query vector (768 dimensions)
     * @param topK           maximum number of results to return
     * @return matching chunks ordered by similarity
     */
    List<ChunkResponseDTO> searchChunksByEmbedding(float[] queryEmbedding, int topK);

    /**
     * Searches for the most relevant timestamped chunks scoped to a specific lecture
     * using vector similarity (cosine distance) against the provided query embedding.
     *
     * @param lectureId      the lecture to scope the search to
     * @param queryEmbedding the query vector (768 dimensions)
     * @param topK           maximum number of results to return
     * @return matching chunks ordered by similarity
     */
    List<ChunkResponseDTO> searchChunksByLectureAndEmbedding(UUID lectureId, float[] queryEmbedding, int topK);
}
