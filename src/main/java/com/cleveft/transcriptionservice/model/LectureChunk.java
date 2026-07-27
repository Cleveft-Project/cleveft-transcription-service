package com.cleveft.transcriptionservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A retrievable slice of a lecture transcript.
 *
 * <p>The {@code embedding} column is deliberately <strong>not</strong> mapped
 * here. pgvector's type has no portable JPA binding, and pulling in a
 * Hibernate-version-specific vector module just to write a column we only ever
 * touch through native SQL buys nothing. Vectors are written by
 * {@link com.cleveft.transcriptionservice.repository.ChunkVectorWriter} and read
 * only by the similarity query, which never needs to materialise them.
 */
@Entity
@Table(name = "chunks", schema = "transcription")
public class LectureChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    /** Denormalised from the lecture so retrieval can filter without a join. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Estimated seconds into the recording; drives "jump to this moment". */
    @Column(name = "start_time")
    private Double startTime;

    @Column(name = "end_time")
    private Double endTime;

    @Column(name = "topic_tag")
    private String topicTag;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public LectureChunk() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return id;
    }

    public Lecture getLecture() {
        return lecture;
    }

    public UUID getUserId() {
        return userId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Double getStartTime() {
        return startTime;
    }

    public Double getEndTime() {
        return endTime;
    }

    public String getTopicTag() {
        return topicTag;
    }

    public void setTopicTag(String topicTag) {
        this.topicTag = topicTag;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public static final class Builder {

        private final LectureChunk chunk = new LectureChunk();

        public Builder lecture(Lecture lecture) {
            chunk.lecture = lecture;
            return this;
        }

        public Builder userId(UUID userId) {
            chunk.userId = userId;
            return this;
        }

        public Builder chunkIndex(Integer chunkIndex) {
            chunk.chunkIndex = chunkIndex;
            return this;
        }

        public Builder content(String content) {
            chunk.content = content;
            return this;
        }

        public Builder startTime(Double startTime) {
            chunk.startTime = startTime;
            return this;
        }

        public Builder endTime(Double endTime) {
            chunk.endTime = endTime;
            return this;
        }

        public Builder topicTag(String topicTag) {
            chunk.topicTag = topicTag;
            return this;
        }

        public LectureChunk build() {
            return chunk;
        }
    }
}
