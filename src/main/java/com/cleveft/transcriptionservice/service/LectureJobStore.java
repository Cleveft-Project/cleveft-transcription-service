package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.model.Lecture;
import com.cleveft.transcriptionservice.model.Lecture.LectureStatus;
import com.cleveft.transcriptionservice.model.LectureChunk;
import com.cleveft.transcriptionservice.repository.LectureChunkRepository;
import com.cleveft.transcriptionservice.repository.LectureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transactional half of the processing pipeline.
 *
 * <p>Separate from {@link LectureProcessor} on purpose. Spring's
 * {@code @Transactional} works through a proxy, so a method calling its own
 * annotated sibling gets no transaction at all. Since the processor has to make
 * many short, independently-committed writes between long AI calls, those writes
 * live here where every call is a genuine cross-bean invocation.
 */
@Service
public class LectureJobStore {

    private final LectureRepository lectureRepository;
    private final LectureChunkRepository chunkRepository;

    public LectureJobStore(LectureRepository lectureRepository, LectureChunkRepository chunkRepository) {
        this.lectureRepository = lectureRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional(readOnly = true)
    public Lecture require(UUID lectureId) {
        return lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalStateException("Lecture " + lectureId + " vanished mid-pipeline"));
    }

    @Transactional
    public Lecture markProcessing(UUID lectureId, String detail) {
        Lecture lecture = require(lectureId);
        lecture.setStatus(LectureStatus.PROCESSING);
        lecture.setStatusDetail(detail);
        return lectureRepository.saveAndFlush(lecture);
    }

    @Transactional
    public void updateStatusDetail(UUID lectureId, String detail) {
        lectureRepository.findById(lectureId).ifPresent(lecture -> {
            lecture.setStatusDetail(detail);
            lectureRepository.saveAndFlush(lecture);
        });
    }

    @Transactional
    public void saveTranscript(UUID lectureId, String transcript) {
        Lecture lecture = require(lectureId);
        lecture.setFullTranscript(transcript);
        lecture.setStatusDetail("Transcript ready. Indexing…");
        lectureRepository.saveAndFlush(lecture);
    }

    /**
     * Replaces every chunk for this lecture.
     *
     * <p>The delete is not optional: re-indexing an edited transcript while the
     * previous vectors survive would keep retrieving the wording the student
     * just corrected.
     *
     * @return the ids of the new chunks, in chunk-index order
     */
    @Transactional
    public List<UUID> replaceChunks(UUID lectureId, List<TranscriptChunker.Chunk> pieces) {
        Lecture lecture = require(lectureId);

        chunkRepository.deleteByLectureId(lectureId);
        chunkRepository.flush();

        List<LectureChunk> chunks = new ArrayList<>(pieces.size());
        for (TranscriptChunker.Chunk piece : pieces) {
            chunks.add(LectureChunk.builder()
                    .lecture(lecture)
                    .userId(lecture.getUserId())
                    .chunkIndex(piece.index())
                    .content(piece.content())
                    .startTime(piece.startTime())
                    .endTime(piece.endTime())
                    .build());
        }

        return chunkRepository.saveAll(chunks).stream()
                .map(LectureChunk::getId)
                .toList();
    }

    /**
     * Applies a topic tag to each chunk. Tags come from the note-structuring
     * pass and are what the exam-prep service groups weak areas by.
     */
    @Transactional
    public void applyTopicTags(UUID lectureId, List<String> tagsByChunkIndex) {
        if (tagsByChunkIndex == null || tagsByChunkIndex.isEmpty()) {
            return;
        }

        List<LectureChunk> chunks = chunkRepository.findByLectureIdOrderByChunkIndexAsc(lectureId);
        for (LectureChunk chunk : chunks) {
            int index = chunk.getChunkIndex();
            if (index < tagsByChunkIndex.size()) {
                chunk.setTopicTag(tagsByChunkIndex.get(index));
            }
        }
        chunkRepository.saveAll(chunks);
    }

    @Transactional
    public void complete(UUID lectureId, NoteStructuringService.StructuredNotes notes) {
        Lecture lecture = require(lectureId);

        if (!notes.isEmpty()) {
            lecture.setStructuredNotes(notes.sections());
            lecture.setKeyConcepts(notes.keyConcepts());
        }
        lecture.setStatus(LectureStatus.COMPLETED);
        lecture.setStatusDetail(null);
        lectureRepository.saveAndFlush(lecture);
    }

    @Transactional
    public void markFailed(UUID lectureId, String reason) {
        lectureRepository.findById(lectureId).ifPresent(lecture -> {
            lecture.setStatus(LectureStatus.FAILED);
            lecture.setStatusDetail(reason);
            lectureRepository.saveAndFlush(lecture);
        });
    }
}
