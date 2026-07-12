package com.cleveft.transcriptionservice.repository;

import com.cleveft.transcriptionservice.model.Lecture;
import com.cleveft.transcriptionservice.model.Lecture.LectureStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface LectureRepository extends JpaRepository<Lecture, UUID> {

    List<Lecture> findByStatus(LectureStatus status);

    List<Lecture> findByStatusOrderByCreatedAtDesc(LectureStatus status);

    List<Lecture> findByTitleContainingIgnoreCase(String keyword);

    List<Lecture> findByCreatedAtAfter(LocalDateTime since);
}
