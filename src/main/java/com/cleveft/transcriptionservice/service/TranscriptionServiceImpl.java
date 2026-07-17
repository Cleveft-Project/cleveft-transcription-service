package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.dto.ChunkResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureResponseDTO;
import com.cleveft.transcriptionservice.dto.TranscriptionRequestDTO;
import com.cleveft.transcriptionservice.model.Lecture;
import com.cleveft.transcriptionservice.model.Lecture.LectureStatus;
import com.cleveft.transcriptionservice.model.LectureChunk;
import com.cleveft.transcriptionservice.repository.LectureChunkRepository;
import com.cleveft.transcriptionservice.repository.LectureRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptionServiceImpl implements TranscriptionService {

    private static final int VECTOR_DIMENSIONS = 768;
    private static final int DEFAULT_CHUNK_COUNT = 5;
    private static final double CHUNK_DURATION_SECONDS = 30.0;

    private final LectureRepository lectureRepository;
    private final LectureChunkRepository chunkRepository;

    // ----------------------------------------------------------------
    //  Process a new lecture
    // ----------------------------------------------------------------

    @Override
    @Transactional
    public LectureResponseDTO processLecture(TranscriptionRequestDTO request) {
        log.info("Processing new lecture: {}", request.getTitle());

        // 1. Persist the lecture record in PENDING state
        Lecture lecture = Lecture.builder()
                .title(request.getTitle())
                .courseCode(request.getCourseCode())
                .sourceUrl(request.getSourceUrl())
                .language(request.getLanguage())
                .durationSeconds(request.getDurationSeconds())
                .status(LectureStatus.PENDING)
                .userId(UUID.randomUUID()) // Mock user ID to satisfy NOT NULL constraint
                .build();

        lecture = lectureRepository.save(lecture);
        log.debug("Lecture saved with ID: {}", lecture.getId());

        // 2. Simulate transcription processing
        try {
            lecture.setStatus(LectureStatus.PROCESSING);
            lecture = lectureRepository.save(lecture);

            List<LectureChunk> chunks = generateMockChunks(lecture);
            chunkRepository.saveAll(chunks);
            log.info("Generated {} mock chunks for lecture {}", chunks.size(), lecture.getId());

            // 3. Build the full transcript from chunks
            String fullTranscript = chunks.stream()
                    .sorted(Comparator.comparingInt(LectureChunk::getChunkIndex))
                    .map(LectureChunk::getContent)
                    .collect(Collectors.joining(" "));

            lecture.setFullTranscript(fullTranscript);
            lecture.setStatus(LectureStatus.COMPLETED);
            lecture = lectureRepository.save(lecture);

            log.info("Lecture {} processing completed successfully", lecture.getId());
        } catch (Exception e) {
            log.error("Failed to process lecture {}: {}", lecture.getId(), e.getMessage(), e);
            lecture.setStatus(LectureStatus.FAILED);
            lectureRepository.save(lecture);
        }

        return mapToLectureResponse(lecture, true);
    }

    // ----------------------------------------------------------------
    //  Retrieve a lecture by ID
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public LectureResponseDTO getLectureById(UUID lectureId) {
        log.debug("Fetching lecture by ID: {}", lectureId);

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Lecture not found with ID: " + lectureId));

        return mapToLectureResponse(lecture, true);
    }

    // ----------------------------------------------------------------
    //  Retrieve all lectures
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<LectureResponseDTO> getAllLectures() {
        log.debug("Fetching all lectures");

        return lectureRepository.findAll().stream()
                .sorted(Comparator.comparing(Lecture::getCreatedAt).reversed())
                .map(lecture -> mapToLectureResponse(lecture, false))
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    //  Vector similarity search — global
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ChunkResponseDTO> searchChunksByEmbedding(float[] queryEmbedding, int topK) {
        log.debug("Searching chunks globally, topK={}", topK);

        String embeddingStr = embeddingToString(queryEmbedding);
        List<LectureChunk> results = chunkRepository.findTopKByCosineDistance(embeddingStr, topK);

        return results.stream()
                .map(this::mapToChunkResponse)
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    //  Vector similarity search — scoped to a lecture
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ChunkResponseDTO> searchChunksByLectureAndEmbedding(UUID lectureId, float[] queryEmbedding, int topK) {
        log.debug("Searching chunks for lecture {}, topK={}", lectureId, topK);

        // Verify the lecture exists
        if (!lectureRepository.existsById(lectureId)) {
            throw new EntityNotFoundException("Lecture not found with ID: " + lectureId);
        }

        String embeddingStr = embeddingToString(queryEmbedding);
        List<LectureChunk> results = chunkRepository.findTopKByLectureAndCosineDistance(
                lectureId, embeddingStr, topK);

        return results.stream()
                .map(this::mapToChunkResponse)
                .collect(Collectors.toList());
    }

    // ================================================================
    //  Private helpers
    // ================================================================

    /**
     * Generates mock transcription chunks with simulated text and random embeddings.
     * In production, this would be replaced by actual ASR + embedding model calls.
     */
    private List<LectureChunk> generateMockChunks(Lecture lecture) {
        int totalChunks = (lecture.getDurationSeconds() != null)
                ? Math.max(1, (int) Math.ceil(lecture.getDurationSeconds() / CHUNK_DURATION_SECONDS))
                : DEFAULT_CHUNK_COUNT;

        List<LectureChunk> chunks = new ArrayList<>();
        Random random = new Random(lecture.getId().hashCode()); // deterministic for same lecture

        for (int i = 0; i < totalChunks; i++) {
            double startTime = i * CHUNK_DURATION_SECONDS;
            double endTime = Math.min(startTime + CHUNK_DURATION_SECONDS,
                    lecture.getDurationSeconds() != null ? lecture.getDurationSeconds() : (i + 1) * CHUNK_DURATION_SECONDS);

            LectureChunk chunk = LectureChunk.builder()
                    .lecture(lecture)
                    .chunkIndex(i)
                    .content(generateMockTranscriptSegment(i, lecture.getTitle()))
                    .startTime(startTime)
                    .endTime(endTime)
                    .embedding(generateMockEmbedding(random))
                    .build();

            chunks.add(chunk);
        }

        return chunks;
    }

    /**
     * Produces a placeholder transcript segment for a given chunk index.
     */
    private String generateMockTranscriptSegment(int chunkIndex, String lectureTitle) {
        return String.format(
                "[Chunk %d] This is a simulated transcript segment for \"%s\". "
                        + "In production, this text would be generated by an ASR engine processing the audio stream.",
                chunkIndex, lectureTitle
        );
    }

    /**
     * Generates a random unit-normalized embedding vector of {@link #VECTOR_DIMENSIONS} dimensions.
     */
    private float[] generateMockEmbedding(Random random) {
        float[] embedding = new float[VECTOR_DIMENSIONS];
        float sumSquares = 0.0f;

        for (int i = 0; i < VECTOR_DIMENSIONS; i++) {
            embedding[i] = (float) random.nextGaussian();
            sumSquares += embedding[i] * embedding[i];
        }

        // L2-normalize so cosine distance is meaningful
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < VECTOR_DIMENSIONS; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    /**
     * Converts a float[] embedding to the pgvector string format: {@code [0.1,0.2,...]}.
     */
    private String embeddingToString(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Query embedding must not be null or empty");
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // ----------------------------------------------------------------
    //  Entity → DTO mapping
    // ----------------------------------------------------------------

    private LectureResponseDTO mapToLectureResponse(Lecture lecture, boolean includeChunks) {
        List<ChunkResponseDTO> chunkDTOs = null;
        int totalChunks;

        if (includeChunks) {
            List<LectureChunk> chunks = chunkRepository.findByLectureIdOrderByChunkIndexAsc(lecture.getId());
            chunkDTOs = chunks.stream()
                    .map(this::mapToChunkResponse)
                    .collect(Collectors.toList());
            totalChunks = chunkDTOs.size();
        } else {
            totalChunks = (int) chunkRepository.countByLectureId(lecture.getId());
        }

        return LectureResponseDTO.builder()
                .id(lecture.getId())
                .title(lecture.getTitle())
                .sourceUrl(lecture.getSourceUrl())
                .language(lecture.getLanguage())
                .durationSeconds(lecture.getDurationSeconds())
                .status(lecture.getStatus())
                .fullTranscript(lecture.getFullTranscript())
                .totalChunks(totalChunks)
                .chunks(chunkDTOs)
                .createdAt(lecture.getCreatedAt())
                .updatedAt(lecture.getUpdatedAt())
                .build();
    }

    private ChunkResponseDTO mapToChunkResponse(LectureChunk chunk) {
        return ChunkResponseDTO.builder()
                .id(chunk.getId())
                .lectureId(chunk.getLecture().getId())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .startTime(chunk.getStartTime())
                .endTime(chunk.getEndTime())
                .createdAt(chunk.getCreatedAt())
                .build();
    }
}
