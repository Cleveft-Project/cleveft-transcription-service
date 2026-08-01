package com.cleveft.transcriptionservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps onto {@code transcription.lectures} from cleveft-infra/init.sql.
 */
@Entity
@Table(name = "lectures", schema = "transcription")
public class Lecture {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Owning student, taken from the gateway-verified {@code X-User-Id}. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "course_code")
    private String courseCode;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    /**
     * The lecture this one was imported to help explain, if any.
     *
     * <p>Students mostly go looking for a video because something in a specific
     * class did not land. Recording that link is what lets the app answer "what
     * else do I have on functional dependencies?" and what keeps supporting
     * material out of the exam readiness calculation — a video explains what you
     * were taught, it does not decide what you will be examined on.
     *
     * <p>Null for anything imported on its own, which stays a perfectly ordinary
     * library item.
     */
    @Column(name = "related_lecture_id")
    private UUID relatedLectureId;

    /** Where the original recording was persisted, for re-processing. */
    @Column(name = "audio_path", length = 1000)
    private String audioPath;

    @Column(name = "language", length = 16)
    private String language;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LectureStatus status = LectureStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private LectureSource source = LectureSource.RECORDING;

    /** Human-readable reason a lecture is still processing or has failed. */
    @Column(name = "status_detail", columnDefinition = "TEXT")
    private String statusDetail;

    @Column(name = "full_transcript", columnDefinition = "TEXT")
    private String fullTranscript;

    /** AI-generated topic-structured notes: [{heading, summary, points[]}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_notes", columnDefinition = "jsonb")
    private List<Map<String, Object>> structuredNotes;

    /** Formulas, theorems and definitions worth surfacing: [{term, kind, detail}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_concepts", columnDefinition = "jsonb")
    private List<Map<String, Object>> keyConcepts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Lecture() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public void setCourseCode(String courseCode) {
        this.courseCode = courseCode;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public UUID getRelatedLectureId() {
        return relatedLectureId;
    }

    public void setRelatedLectureId(UUID relatedLectureId) {
        this.relatedLectureId = relatedLectureId;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public LectureStatus getStatus() {
        return status;
    }

    public void setStatus(LectureStatus status) {
        this.status = status;
    }

    public String getStatusDetail() {
        return statusDetail;
    }

    public void setStatusDetail(String statusDetail) {
        this.statusDetail = statusDetail;
    }

    public String getFullTranscript() {
        return fullTranscript;
    }

    public void setFullTranscript(String fullTranscript) {
        this.fullTranscript = fullTranscript;
    }

    public List<Map<String, Object>> getStructuredNotes() {
        return structuredNotes;
    }

    public void setStructuredNotes(List<Map<String, Object>> structuredNotes) {
        this.structuredNotes = structuredNotes;
    }

    public List<Map<String, Object>> getKeyConcepts() {
        return keyConcepts;
    }

    public void setKeyConcepts(List<Map<String, Object>> keyConcepts) {
        this.keyConcepts = keyConcepts;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LectureSource getSource() {
        return source;
    }

    public void setSource(LectureSource source) {
        this.source = source;
    }

    public enum LectureStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * Where a lecture's words came from.
     *
     * <p>Only affects how the transcript was obtained — everything downstream
     * treats all three identically. It is stored because the student needs to
     * know at a glance that a "lecture" is really a handout they imported, and
     * because retry has to re-run the right pipeline.
     */
    public enum LectureSource {
        RECORDING,
        PDF,
        YOUTUBE
    }

    public static final class Builder {

        private final Lecture lecture = new Lecture();

        public Builder userId(UUID userId) {
            lecture.userId = userId;
            return this;
        }

        public Builder title(String title) {
            lecture.title = title;
            return this;
        }

        public Builder courseCode(String courseCode) {
            lecture.courseCode = courseCode;
            return this;
        }

        public Builder sourceUrl(String sourceUrl) {
            lecture.sourceUrl = sourceUrl;
            return this;
        }

        public Builder source(LectureSource source) {
            lecture.source = source;
            return this;
        }

        public Builder relatedLectureId(UUID relatedLectureId) {
            lecture.relatedLectureId = relatedLectureId;
            return this;
        }

        public Builder language(String language) {
            lecture.language = language;
            return this;
        }

        public Builder durationSeconds(Integer durationSeconds) {
            lecture.durationSeconds = durationSeconds;
            return this;
        }

        public Builder status(LectureStatus status) {
            lecture.status = status;
            return this;
        }

        public Builder statusDetail(String statusDetail) {
            lecture.statusDetail = statusDetail;
            return this;
        }

        public Lecture build() {
            return lecture;
        }
    }
}
