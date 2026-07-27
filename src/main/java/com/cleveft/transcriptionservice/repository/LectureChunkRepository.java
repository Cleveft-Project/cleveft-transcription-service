package com.cleveft.transcriptionservice.repository;

import com.cleveft.transcriptionservice.model.LectureChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LectureChunkRepository extends JpaRepository<LectureChunk, UUID> {

    List<LectureChunk> findByLectureIdOrderByChunkIndexAsc(UUID lectureId);

    long countByLectureId(UUID lectureId);

    /**
     * The distinct topic tags assigned to this lecture's chunks.
     *
     * <p>This is the canonical topic vocabulary for the platform: the same tags
     * the exam-prep service records mastery against. Key-concept <em>terms</em>
     * are written by a different prompt for display and must not be used to
     * decide what has or has not been covered.
     */
    @Query("""
            SELECT DISTINCT c.topicTag FROM LectureChunk c
            WHERE c.lecture.id = :lectureId AND c.topicTag IS NOT NULL
            """)
    List<String> findDistinctTopicTags(@Param("lectureId") UUID lectureId);

    @Modifying
    @Query("DELETE FROM LectureChunk c WHERE c.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") UUID lectureId);

    /**
     * Cosine-distance nearest neighbours across everything this student owns.
     *
     * <p>Scoped by {@code user_id} inside the SQL rather than filtered
     * afterwards: filtering post-hoc would let another student's chunks consume
     * the top-K slots and silently starve the result set.
     *
     * <p>{@code embedding <=> :vector} returns cosine <em>distance</em>, so
     * similarity is {@code 1 - distance}.
     */
    @Query(value = """
            SELECT c.id, c.lecture_id, c.user_id, c.chunk_index, c.content,
                   c.start_time, c.end_time, c.topic_tag, c.created_at,
                   1 - (c.embedding <=> CAST(:vector AS vector)) AS similarity,
                   l.title AS lecture_title
            FROM transcription.chunks c
            JOIN transcription.lectures l ON l.id = c.lecture_id
            WHERE c.user_id = :userId
              AND c.embedding IS NOT NULL
              AND (CAST(:lectureId AS uuid) IS NULL OR c.lecture_id = CAST(:lectureId AS uuid))
            ORDER BY c.embedding <=> CAST(:vector AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkMatch> searchByEmbedding(
            @Param("userId") UUID userId,
            @Param("lectureId") String lectureId,
            @Param("vector") String vector,
            @Param("topK") int topK);

    /**
     * Projection for the similarity search. Spring Data maps native result
     * columns onto these accessors by name.
     */
    interface ChunkMatch {
        UUID getId();

        UUID getLectureId();

        Integer getChunkIndex();

        String getContent();

        Double getStartTime();

        Double getEndTime();

        String getTopicTag();

        Double getSimilarity();

        String getLectureTitle();
    }
}
