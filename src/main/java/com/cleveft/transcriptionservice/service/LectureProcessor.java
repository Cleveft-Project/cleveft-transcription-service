package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.ai.AiServiceException;
import com.cleveft.transcriptionservice.ai.EmbeddingProvider;
import com.cleveft.transcriptionservice.ai.SttProvider;
import com.cleveft.transcriptionservice.ai.VideoProvider;
import com.cleveft.transcriptionservice.client.NotificationClient;
import com.cleveft.transcriptionservice.model.Lecture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the transcription pipeline off the request thread.
 *
 * <p>Transcribing an hour of audio takes minutes. Holding an HTTP connection
 * open for that is hostile on a mobile network, so the upload returns
 * immediately with a {@code PENDING} lecture and the client polls
 * {@code /{id}/status}.
 *
 * <p>All persistence goes through {@link LectureJobStore} so each stage commits
 * on its own — a long transaction spanning the AI calls would pin a database
 * connection for the whole job and throw away a perfectly good transcript if a
 * later stage failed.
 */
@Service
public class LectureProcessor {

    private static final Logger log = LoggerFactory.getLogger(LectureProcessor.class);

    private final LectureJobStore store;
    private final com.cleveft.transcriptionservice.repository.ChunkVectorWriter vectorWriter;
    private final SttProvider sttProvider;
    private final EmbeddingProvider embeddingProvider;
    private final TranscriptChunker chunker;
    private final NoteStructuringService noteStructuringService;
    private final DocumentTextExtractor documentTextExtractor;
    private final VideoProvider videoProvider;
    private final NotificationClient notifications;

    public LectureProcessor(LectureJobStore store,
                            com.cleveft.transcriptionservice.repository.ChunkVectorWriter vectorWriter,
                            SttProvider sttProvider,
                            EmbeddingProvider embeddingProvider,
                            TranscriptChunker chunker,
                            NoteStructuringService noteStructuringService,
                            DocumentTextExtractor documentTextExtractor,
                            VideoProvider videoProvider,
                            NotificationClient notifications) {
        this.store = store;
        this.notifications = notifications;
        this.vectorWriter = vectorWriter;
        this.sttProvider = sttProvider;
        this.embeddingProvider = embeddingProvider;
        this.chunker = chunker;
        this.noteStructuringService = noteStructuringService;
        this.documentTextExtractor = documentTextExtractor;
        this.videoProvider = videoProvider;
    }

    /**
     * Transcribe, then index. Audio is handed over in memory rather than re-read
     * from disk, so the pipeline still runs with audio retention switched off.
     */
    @Async("transcriptionExecutor")
    public void processAudio(UUID lectureId, byte[] audio, String mimeType) {
        log.info("Starting transcription pipeline for lecture {} ({} bytes)", lectureId, audio.length);

        try {
            Lecture lecture = store.markProcessing(lectureId, "Transcribing your lecture…");

            String transcript = sttProvider.transcribe(audio, mimeType, lecture.getLanguage());
            store.saveTranscript(lectureId, transcript);

            index(lectureId, transcript);
            log.info("Lecture {} completed ({} characters transcribed)", lectureId, transcript.length());

        } catch (AiServiceException e) {
            log.error("Transcription failed for lecture {}: {}", lectureId, e.getMessage());
            fail(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure processing lecture {}", lectureId, e);
            fail(lectureId, "Processing failed unexpectedly. Please try again.");
        }
    }

    /**
     * The same pipeline, entered from a document instead of a microphone.
     *
     * <p>A PDF and a recording differ in exactly one stage: where the words come
     * from. Extraction replaces speech-to-text, and from {@link #index} onwards
     * the two are the same job — which is why an imported handout gets notes,
     * chunks, embeddings, topic tags, quizzes and RAG citations without a line
     * of that being written twice.
     *
     * <p>Deliberately not folded into {@link #processAudio} behind a flag: the
     * two differ in their failure modes as much as their inputs, and a shared
     * method would spend most of its body deciding which kind of job it was.
     */
    @Async("transcriptionExecutor")
    public void processDocument(UUID lectureId, byte[] document, String fileName) {
        log.info("Starting document pipeline for lecture {} ({} bytes)", lectureId, document.length);

        try {
            store.markProcessing(lectureId, "Reading your document…");

            String transcript = documentTextExtractor.extract(document, fileName);
            store.saveTranscript(lectureId, transcript);

            index(lectureId, transcript);
            log.info("Lecture {} completed from document ({} characters)", lectureId, transcript.length());

        } catch (DocumentTextExtractor.DocumentExtractionException e) {
            // These messages are written for the student — a scanned PDF or a
            // password-protected one is a thing they can act on, not an error.
            log.info("Document rejected for lecture {}: {}", lectureId, e.getMessage());
            fail(lectureId, e.getMessage());
        } catch (AiServiceException e) {
            log.error("Indexing failed for document lecture {}: {}", lectureId, e.getMessage());
            fail(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure processing document lecture {}", lectureId, e);
            fail(lectureId, "Processing failed unexpectedly. Please try again.");
        }
    }

    /**
     * The same pipeline again, entered from a link.
     *
     * <p>Nothing here downloads anything: the URL is handed to the model, which
     * fetches the video itself. That is what keeps this free of a media
     * downloader, ffmpeg and the container bloat both would bring — and on the
     * right side of YouTube's terms.
     *
     * <p>Unlike a recording or a PDF, the source survives the import, because a
     * URL costs nothing to keep. {@code retryProcessing} uses that to re-read
     * the video outright when the first read produced nothing.
     */
    @Async("transcriptionExecutor")
    public void processVideo(UUID lectureId, String url) {
        log.info("Starting video pipeline for lecture {} ({})", lectureId, url);

        try {
            store.markProcessing(lectureId, "Watching the video…");

            String transcript = videoProvider.describe(url);
            store.saveTranscript(lectureId, transcript);

            index(lectureId, transcript);
            log.info("Lecture {} completed from video ({} characters)", lectureId, transcript.length());

        } catch (AiServiceException e) {
            log.error("Video import failed for lecture {}: {}", lectureId, e.getMessage());
            fail(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure processing video lecture {}", lectureId, e);
            fail(lectureId, "Processing failed unexpectedly. Please try again.");
        }
    }

    /**
     * Re-runs everything downstream of speech-to-text after a student edits the
     * transcript by hand.
     */
    @Async("transcriptionExecutor")
    public void reindexTranscript(UUID lectureId, String transcript) {
        log.info("Re-indexing edited transcript for lecture {}", lectureId);

        try {
            store.markProcessing(lectureId, "Re-indexing your edits…");
            index(lectureId, transcript);
        } catch (AiServiceException e) {
            log.error("Re-indexing failed for lecture {}: {}", lectureId, e.getMessage());
            fail(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure re-indexing lecture {}", lectureId, e);
            fail(lectureId, "Re-indexing failed unexpectedly.");
        }
    }

    // ------------------------------------------------------------------

    private void index(UUID lectureId, String transcript) {
        Lecture lecture = store.require(lectureId);

        store.updateStatusDetail(lectureId, "Building your searchable knowledge base…");
        List<TranscriptChunker.Chunk> pieces = chunker.chunk(transcript, lecture.getDurationSeconds());

        if (pieces.isEmpty()) {
            fail(lectureId, "The transcript was empty, so there was nothing to index.");
            return;
        }

        List<UUID> chunkIds = store.replaceChunks(lectureId, pieces);

        List<String> texts = pieces.stream().map(TranscriptChunker.Chunk::content).toList();
        List<float[]> embeddings = embeddingProvider.embedDocuments(texts);
        vectorWriter.writeAll(chunkIds, embeddings);

        store.updateStatusDetail(lectureId, "Organising your notes…");
        NoteStructuringService.StructuredNotes notes =
                noteStructuringService.structure(transcript, lecture.getTitle());

        store.applyTopicTags(lectureId, spreadTopicsAcrossChunks(notes.topics(), pieces.size()));
        store.complete(lectureId, notes);

        // After complete() returns, so the write has committed. Announcing it
        // from inside that transaction would risk telling a student their
        // lecture is ready and then failing the commit that made it so.
        announceReady(lectureId, lecture);
    }

    /**
     * Tells the student their lecture is done.
     *
     * <p>This is the notification the whole feature exists for. Processing
     * finishes long after they have left the hall and put the phone away, and
     * without it they have to keep opening Cleveft to check — which is exactly
     * the behaviour the app is supposed to remove.
     */
    private void announceReady(UUID lectureId, Lecture lecture) {
        String title = lecture.getTitle() == null || lecture.getTitle().isBlank()
                ? "Your lecture"
                : lecture.getTitle();

        notifications.notify(
                lecture.getUserId(),
                "LECTURE_READY",
                "Your notes are ready",
                title + " is transcribed and ready to question.",
                Map.of("screen", "transcript", "lectureId", lectureId.toString()));
    }

    /**
     * Marks the job failed and tells the student.
     *
     * <p>Every failure path goes through here rather than calling the store
     * directly, because a silent failure is the worst outcome this pipeline has.
     * A student who recorded fifty minutes and hears nothing assumes it is still
     * working, and only finds out the evening before the exam.
     *
     * <p>Carried on the same preference as success. Someone who wants to know
     * when a lecture is ready wants to know when it is not, and splitting that
     * into two switches would offer a combination nobody would ever choose.
     */
    private void fail(UUID lectureId, String reason) {
        store.markFailed(lectureId, reason);

        try {
            Lecture lecture = store.require(lectureId);
            String title = lecture.getTitle() == null || lecture.getTitle().isBlank()
                    ? "Your lecture"
                    : lecture.getTitle();

            notifications.notify(
                    lecture.getUserId(),
                    "LECTURE_READY",
                    "That lecture did not finish",
                    title + " — " + reason,
                    // Straight to the lecture, where the retry button is. A
                    // notification about a problem should land on the fix.
                    Map.of("screen", "transcript", "lectureId", lectureId.toString()));

        } catch (Exception e) {
            // The failure is already recorded, which is the part that matters.
            log.warn("Could not notify about failed lecture {}: {}", lectureId, e.getMessage());
        }
    }

    /**
     * Assigns each chunk the topic covering its position in the lecture.
     *
     * <p>Topics come back in lecture order, so mapping them proportionally onto
     * chunk positions is a reasonable approximation of where each subject was
     * discussed — good enough to group weak areas by, without a second AI pass
     * per chunk.
     */
    private List<String> spreadTopicsAcrossChunks(List<String> topics, int chunkCount) {
        if (topics == null || topics.isEmpty() || chunkCount == 0) {
            return List.of();
        }

        return java.util.stream.IntStream.range(0, chunkCount)
                .mapToObj(index -> topics.get(Math.min(
                        topics.size() - 1,
                        index * topics.size() / chunkCount)))
                .toList();
    }
}
