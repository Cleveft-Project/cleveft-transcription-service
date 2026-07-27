package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.ai.AiServiceException;
import com.cleveft.transcriptionservice.ai.EmbeddingProvider;
import com.cleveft.transcriptionservice.ai.SttProvider;
import com.cleveft.transcriptionservice.model.Lecture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public LectureProcessor(LectureJobStore store,
                            com.cleveft.transcriptionservice.repository.ChunkVectorWriter vectorWriter,
                            SttProvider sttProvider,
                            EmbeddingProvider embeddingProvider,
                            TranscriptChunker chunker,
                            NoteStructuringService noteStructuringService,
                            DocumentTextExtractor documentTextExtractor) {
        this.store = store;
        this.vectorWriter = vectorWriter;
        this.sttProvider = sttProvider;
        this.embeddingProvider = embeddingProvider;
        this.chunker = chunker;
        this.noteStructuringService = noteStructuringService;
        this.documentTextExtractor = documentTextExtractor;
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
            store.markFailed(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure processing lecture {}", lectureId, e);
            store.markFailed(lectureId, "Processing failed unexpectedly. Please try again.");
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
            store.markFailed(lectureId, e.getMessage());
        } catch (AiServiceException e) {
            log.error("Indexing failed for document lecture {}: {}", lectureId, e.getMessage());
            store.markFailed(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure processing document lecture {}", lectureId, e);
            store.markFailed(lectureId, "Processing failed unexpectedly. Please try again.");
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
            store.markFailed(lectureId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected failure re-indexing lecture {}", lectureId, e);
            store.markFailed(lectureId, "Re-indexing failed unexpectedly.");
        }
    }

    // ------------------------------------------------------------------

    private void index(UUID lectureId, String transcript) {
        Lecture lecture = store.require(lectureId);

        store.updateStatusDetail(lectureId, "Building your searchable knowledge base…");
        List<TranscriptChunker.Chunk> pieces = chunker.chunk(transcript, lecture.getDurationSeconds());

        if (pieces.isEmpty()) {
            store.markFailed(lectureId, "The transcript was empty, so there was nothing to index.");
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
