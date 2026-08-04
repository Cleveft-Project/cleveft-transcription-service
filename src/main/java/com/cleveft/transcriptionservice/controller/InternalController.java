package com.cleveft.transcriptionservice.controller;

import com.cleveft.transcriptionservice.repository.LectureRepository;
import com.cleveft.transcriptionservice.service.AudioStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service surface, deliberately outside {@code /api/v1/**}.
 *
 * <p>The gateway routes {@code /api/v1/transcriptions/**} here and nothing
 * else, so {@code /internal/**} has no route and cannot be reached from a phone
 * — only from inside the container network. That matters, because this takes a
 * list of user ids rather than deriving one from a token.
 *
 * <p>It returns <em>counts</em> and nothing else. The course leaderboard needs
 * to know that somebody recorded four lectures this week; it must never be able
 * to learn what they were.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);

    private final LectureRepository lectureRepository;
    private final AudioStorage audioStorage;

    public InternalController(LectureRepository lectureRepository, AudioStorage audioStorage) {
        this.lectureRepository = lectureRepository;
        this.audioStorage = audioStorage;
    }

    /**
     * Erases everything this service holds for a student.
     *
     * <p>Called by the auth service when an account is deleted. Audio files are
     * removed before the rows, because the rows are the only record of where
     * those files are — delete them first and the recordings are orphaned on
     * disk forever, which is the opposite of what someone asking to be deleted
     * wanted.
     */
    @DeleteMapping("/users/{userId}")
    @Transactional
    public ResponseEntity<Void> eraseUser(@PathVariable UUID userId) {
        List<String> audio = lectureRepository.audioPathsFor(userId);
        audio.forEach(audioStorage::delete);

        int removed = lectureRepository.deleteByUserId(userId);
        log.info("Erased {} lecture(s) and {} audio file(s) for {}", removed, audio.size(), userId);

        return ResponseEntity.noContent().build();
    }

    /**
     * How many lectures each of these students added since {@code since}.
     *
     * <p>Students with none are simply absent from the map rather than present
     * with a zero — the caller is ranking, and an absent key and a zero mean
     * the same thing to it.
     */
    @GetMapping("/activity/lectures")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<UUID, Long>> lectureCounts(
            @RequestParam("userIds") List<UUID> userIds,
            @RequestParam("since") OffsetDateTime since) {

        if (userIds.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }

        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : lectureRepository.countByUsersSince(userIds, since)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return ResponseEntity.ok(counts);
    }
}
