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
