package com.cleveft.transcriptionservice.repository;

import com.cleveft.transcriptionservice.model.LectureChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LectureChunkRepository extends JpaRepository<LectureChunk, UUID> {

    List<LectureChunk> findByLectureIdOrderByChunkIndexAsc(UUID lectureId);

    /**
     * Find the top-K most similar chunks across all lectures using pgvector
     * cosine distance operator ({@code <=>}).
     *
     * @param embedding  the query embedding vector cast to a pgvector string
     * @param limit      maximum number of results to return
     * @return chunks ordered by cosine distance (most similar first)
     */
    @Query(value = """
            SELECT c.* FROM transcription.chunks c
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<LectureChunk> findTopKByCosineDistance(
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    /**
     * Find the top-K most similar chunks scoped to a specific lecture
     * using pgvector cosine distance operator ({@code <=>}).
     *
     * @param lectureId  the lecture to scope the search to
     * @param embedding  the query embedding vector cast to a pgvector string
     * @param limit      maximum number of results to return
     * @return chunks ordered by cosine distance (most similar first)
     */
    @Query(value = """
            SELECT c.* FROM transcription.chunks c
            WHERE c.lecture_id = :lectureId
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<LectureChunk> findTopKByLectureAndCosineDistance(
            @Param("lectureId") UUID lectureId,
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    /**
     * Find the top-K most similar chunks using pgvector L2 (Euclidean)
     * distance operator ({@code <->}).
     *
     * @param embedding  the query embedding vector cast to a pgvector string
     * @param limit      maximum number of results to return
     * @return chunks ordered by L2 distance (nearest first)
     */
    @Query(value = """
            SELECT c.* FROM transcription.chunks c
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <-> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<LectureChunk> findTopKByL2Distance(
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    long countByLectureId(UUID lectureId);

    void deleteByLectureId(UUID lectureId);
}
