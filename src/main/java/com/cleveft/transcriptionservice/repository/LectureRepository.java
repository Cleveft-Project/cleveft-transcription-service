package com.cleveft.transcriptionservice.repository;

import com.cleveft.transcriptionservice.model.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
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
