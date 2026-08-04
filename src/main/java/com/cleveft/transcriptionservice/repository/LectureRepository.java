package com.cleveft.transcriptionservice.repository;

import com.cleveft.transcriptionservice.model.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LectureRepository extends JpaRepository<Lecture, UUID> {

    List<Lecture> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Lecture> findTop5ByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Ownership is enforced in the query, never after the fact. */
    Optional<Lecture> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    /** Audio paths for a student, so the files can be removed with the rows. */
    @Query("select l.audioPath from Lecture l where l.userId = :userId and l.audioPath is not null")
    List<String> audioPathsFor(@Param("userId") UUID userId);

    /**
     * Erases a student's lectures.
     *
     * <p>Chunks carry {@code ON DELETE CASCADE} on their lecture, so they go
     * with these — but the embeddings live in those chunk rows, which is the
     * bulk of what a student's account actually weighs.
     */
    @Modifying
    @Query("delete from Lecture l where l.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Lectures added per student since a point in time, for a set of students.
     *
     * <p>One query for a whole cohort rather than one per person — a course
     * leaderboard asks about nine or thirty students at once, and a call each
     * would make opening a tab a burst of database round trips.
     *
     * <p>Returns counts only. Nothing about what anyone recorded ever leaves
     * this service for another student's benefit.
     */
    @Query("""
            select l.userId, count(l)
              from Lecture l
             where l.userId in :userIds
               and l.createdAt >= :since
             group by l.userId
            """)
    List<Object[]> countByUsersSince(@Param("userIds") List<UUID> userIds,
                                     @Param("since") OffsetDateTime since);

    /**
     * Finds an earlier import of the same link.
     *
     * <p>Matches on the canonical URL, which is why {@link
     * com.cleveft.transcriptionservice.service.YouTubeUrl} rebuilds it rather
     * than storing whatever was pasted — a mobile link and a link with a
     * timestamp on it are the same video and must collide here.
     */
    Optional<Lecture> findFirstByUserIdAndSourceUrl(UUID userId, String sourceUrl);

    /** Supporting material gathered around one lecture. */
    List<Lecture> findByUserIdAndRelatedLectureIdOrderByCreatedAtDesc(UUID userId, UUID relatedLectureId);

    long countByUserIdAndStatus(UUID userId, Lecture.LectureStatus status);

    /**
     * Recordings started since a point in time — the Free tier's monthly usage.
     *
     * <p>Counts every lecture created in the window, including failed ones. A
     * failure that already consumed a Gemini transcription call still cost us
     * the work, and not counting them would let a student retry their way past
     * the cap.
     */
    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, OffsetDateTime since);
}
