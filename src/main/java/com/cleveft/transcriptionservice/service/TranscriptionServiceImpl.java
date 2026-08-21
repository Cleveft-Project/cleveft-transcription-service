package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.ai.AudioMimeResolver;
import com.cleveft.transcriptionservice.ai.EmbeddingProvider;
import com.cleveft.transcriptionservice.ai.SttProvider;
import com.cleveft.transcriptionservice.client.AuthPlanClient;
import com.cleveft.transcriptionservice.dto.ChunkMatchDTO;
import com.cleveft.transcriptionservice.dto.ChunkResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureResponseDTO;
import com.cleveft.transcriptionservice.dto.LectureStatusDTO;
import com.cleveft.transcriptionservice.dto.LectureSummaryDTO;
import com.cleveft.transcriptionservice.dto.SearchRequestDTO;
import com.cleveft.transcriptionservice.dto.TranscribedSpeechDTO;
import com.cleveft.transcriptionservice.dto.UpdateLectureRequestDTO;
import com.cleveft.transcriptionservice.dto.UsageDTO;
import com.cleveft.transcriptionservice.exception.ApiException;
import com.cleveft.transcriptionservice.model.Lecture;
import com.cleveft.transcriptionservice.model.Lecture.LectureStatus;
import com.cleveft.transcriptionservice.repository.ChunkVectorWriter;
import com.cleveft.transcriptionservice.repository.LectureChunkRepository;
import com.cleveft.transcriptionservice.repository.LectureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class TranscriptionServiceImpl implements TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionServiceImpl.class);

    private final LectureRepository lectureRepository;
    private final LectureChunkRepository chunkRepository;
    private final LectureProcessor processor;
    private final AudioStorage audioStorage;
    private final AudioMimeResolver mimeResolver;
    private final EmbeddingProvider embeddingProvider;
    private final AuthPlanClient planClient;
    private final SttProvider sttProvider;

    public TranscriptionServiceImpl(LectureRepository lectureRepository,
                                    LectureChunkRepository chunkRepository,
                                    LectureProcessor processor,
                                    AudioStorage audioStorage,
                                    AudioMimeResolver mimeResolver,
                                    EmbeddingProvider embeddingProvider,
                                    AuthPlanClient planClient,
                                    SttProvider sttProvider) {
        this.lectureRepository = lectureRepository;
        this.chunkRepository = chunkRepository;
        this.processor = processor;
        this.audioStorage = audioStorage;
        this.mimeResolver = mimeResolver;
        this.embeddingProvider = embeddingProvider;
        this.planClient = planClient;
        this.sttProvider = sttProvider;
    }

    @Override
    public TranscribedSpeechDTO transcribeSpeech(MultipartFile audio, String language) {
        if (audio == null || audio.isEmpty()) {
            throw ApiException.badRequest("No audio was received.");
        }
        if (!mimeResolver.isSupported(audio.getOriginalFilename(), audio.getContentType())) {
            throw ApiException.badRequest(
                    "Unsupported audio format. Supported: " + mimeResolver.supportedExtensions() + ".");
        }

        String mimeType = mimeResolver.resolve(audio.getOriginalFilename(), audio.getContentType());

        try {
            String text = sttProvider.transcribe(audio.getBytes(), mimeType, language);
            // Silence is not a failure. A student who pressed and thought better
            // of it should get an empty box back, not an error to dismiss.
            return new TranscribedSpeechDTO(text == null ? "" : text.trim());
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the recording.");
        }
    }

    @Override
    public LectureResponseDTO submitRecording(UUID userId,
                                              MultipartFile audio,
                                              String title,
                                              String courseCode,
                                              String language,
                                              Integer durationSeconds) {

        if (audio == null || audio.isEmpty()) {
            throw ApiException.badRequest("No audio file was received.");
        }
        if (!mimeResolver.isSupported(audio.getOriginalFilename(), audio.getContentType())) {
            throw ApiException.badRequest(
                    "Unsupported audio format. Supported: " + mimeResolver.supportedExtensions() + ".");
        }

        // Checked before the bytes are read and stored — refusing after writing
        // the file to disk would leave orphaned audio behind on every rejection.
        requireQuota(userId);

        byte[] content;
        try {
            content = audio.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The upload was interrupted. Please try again.");
        }

        String mimeType = mimeResolver.resolve(audio.getOriginalFilename(), audio.getContentType());

        Lecture lecture = createPendingLecture(userId, title, courseCode, language, durationSeconds);
        String storedPath = audioStorage.store(lecture.getId(), content, audio.getOriginalFilename());
        if (storedPath != null) {
            attachAudioPath(lecture.getId(), storedPath);
        }

        // Hand off to the background pipeline. The client gets its lecture id now
        // and polls for progress.
        processor.processAudio(lecture.getId(), content, mimeType);

        log.info("Queued lecture {} for user {} ({} bytes, {})",
                lecture.getId(), userId, content.length, mimeType);

        return LectureResponseDTO.of(lecture, List.of(), 0);
    }

    @Override
    public LectureResponseDTO submitDocument(UUID userId,
                                             MultipartFile document,
                                             String title,
                                             String courseCode) {

        if (document == null || document.isEmpty()) {
            throw ApiException.badRequest("No file was received.");
        }
        if (!looksLikePdf(document)) {
            throw ApiException.badRequest("Only PDF files can be imported at the moment.");
        }

        // Same check as a recording, and for the same reason: an import consumes
        // a slot on the free plan exactly as a recording does. Charging
        // differently for the same downstream work would be arbitrary, and
        // leaving imports unmetered would make the cap trivial to sidestep.
        requireQuota(userId);

        byte[] content;
        try {
            content = document.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The upload was interrupted. Please try again.");
        }

        String fileName = document.getOriginalFilename();

        // Duration is deliberately null: a PDF has no length in seconds, and
        // inventing one from its page count would put a fictional "12 min" on
        // the lecture card. The chunker already handles a null duration by
        // omitting per-chunk timestamps.
        Lecture lecture = lectureRepository.save(Lecture.builder()
                .userId(userId)
                .title(documentTitle(title, fileName))
                .courseCode(blankToNull(courseCode))
                .language("en")
                .source(Lecture.LectureSource.PDF)
                .status(LectureStatus.PENDING)
                .statusDetail("Queued for import…")
                .build());

        processor.processDocument(lecture.getId(), content, fileName);

        log.info("Queued document lecture {} for user {} ({} bytes, {})",
                lecture.getId(), userId, content.length, fileName);

        return LectureResponseDTO.of(lecture, List.of(), 0);
    }

    @Override
    public LectureResponseDTO submitVideo(UUID userId,
                                          String url,
                                          String title,
                                          String courseCode,
                                          UUID relatedLectureId) {

        YouTubeUrl video;
        try {
            video = YouTubeUrl.parse(url);
        } catch (YouTubeUrl.InvalidVideoUrlException e) {
            // Its messages are written for the student, so they pass straight
            // through rather than being replaced with a generic 400.
            throw ApiException.badRequest(e.getMessage());
        }

        // Ownership is checked before anything is created, so a student cannot
        // attach material to somebody else's lecture by guessing an id.
        if (relatedLectureId != null) {
            requireOwned(userId, relatedLectureId);
        }

        // The same video twice would split one topic's chunks across two rows
        // and quietly halve the retrieval quality for it.
        lectureRepository.findFirstByUserIdAndSourceUrl(userId, video.canonical())
                .ifPresent(existing -> {
                    throw ApiException.badRequest(
                            "You have already imported that video as \"" + existing.getTitle() + "\".");
                });

        requireQuota(userId);

        // Title is deliberately provisional. The real one comes from the video
        // itself, and asking a student to name something they have not watched
        // yet is a worse question than showing them a placeholder that fixes
        // itself once the notes come back.
        Lecture lecture = lectureRepository.save(Lecture.builder()
                .userId(userId)
                .title(blankToNull(title) == null ? "Imported video" : title.trim())
                .courseCode(blankToNull(courseCode))
                .language("en")
                .source(Lecture.LectureSource.YOUTUBE)
                .sourceUrl(video.canonical())
                .relatedLectureId(relatedLectureId)
                .status(LectureStatus.PENDING)
                .statusDetail("Queued for import…")
                .build());

        processor.processVideo(lecture.getId(), video.canonical());

        log.info("Queued video lecture {} for user {} ({}, related to {})",
                lecture.getId(), userId, video.canonical(), relatedLectureId);

        return LectureResponseDTO.of(lecture, List.of(), 0);
    }

    @Override
    @Transactional(readOnly = true)
    public UsageDTO usage(UUID userId) {
        AuthPlanClient.PlanSummary plan = planClient.planFor(userId);
        OffsetDateTime periodStart = startOfMonth();

        long used = lectureRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, periodStart);
        Integer limit = plan.monthlyRecordingLimit();

        return new UsageDTO(
                // The client only knows FREE and PRO. If auth was unreachable the
                // tier is genuinely unknown, and FREE is the honest guess — the
                // null limit below still says "allowance unknown", so the card
                // shows the count without inventing a cap.
                plan.isPro() ? "PRO" : "FREE",
                (int) used,
                limit,
                limit == null ? null : Math.max(0, limit - (int) used),
                periodStart.plusMonths(1));
    }

    /**
     * Rejects the upload when a Free-tier student has used their month.
     *
     * <p>402 rather than 403: the request is well-formed and the student is
     * perfectly entitled to make it — they just have to be on a paid tier. The
     * app keys its upgrade prompt off that status.
     */
    private void requireQuota(UUID userId) {
        AuthPlanClient.PlanSummary plan = planClient.planFor(userId);
        if (plan.isUnlimited()) {
            return;
        }

        int limit = plan.monthlyRecordingLimit();
        long used = lectureRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, startOfMonth());

        if (used >= limit) {
            log.info("User {} hit the {} recording cap ({}/{})", userId, plan.plan(), used, limit);
            throw ApiException.quotaExceeded(
                    "You have used all " + limit + " recordings on your free plan this month. "
                            + "Upgrade to Cleveft Pro for unlimited recordings.");
        }
    }

    /**
     * Calendar months, in UTC, so the allowance resets on a date the student can
     * predict. A rolling 30-day window would be fairer but means never being
     * able to answer "when do I get more?" with a date.
     */
    private static OffsetDateTime startOfMonth() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS);
    }

    /**
     * Not annotated: {@code submitRecording} calls this on itself, and a
     * self-invocation never passes through the transactional proxy. The
     * repository's own save is transactional, which is all this needs.
     */
    private Lecture createPendingLecture(UUID userId, String title, String courseCode,
                                         String language, Integer durationSeconds) {
        return lectureRepository.save(Lecture.builder()
                .userId(userId)
                .title(defaultedTitle(title))
                .courseCode(blankToNull(courseCode))
                .language(language == null || language.isBlank() ? "en" : language)
                .durationSeconds(durationSeconds)
                .status(LectureStatus.PENDING)
                .statusDetail("Queued for transcription…")
                .build());
    }

    private void attachAudioPath(UUID lectureId, String path) {
        lectureRepository.findById(lectureId).ifPresent(lecture -> {
            lecture.setAudioPath(path);
            lectureRepository.save(lecture);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<LectureSummaryDTO> listLectures(UUID userId) {
        return lectureRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(lecture -> LectureSummaryDTO.of(
                        lecture,
                        (int) chunkRepository.countByLectureId(lecture.getId()),
                        chunkRepository.findDistinctTopicTags(lecture.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LectureResponseDTO getLecture(UUID userId, UUID lectureId) {
        Lecture lecture = requireOwned(userId, lectureId);

        List<ChunkResponseDTO> chunks = chunkRepository
                .findByLectureIdOrderByChunkIndexAsc(lectureId).stream()
                .map(ChunkResponseDTO::from)
                .toList();

        return LectureResponseDTO.of(lecture, chunks, chunks.size());
    }

    @Override
    @Transactional(readOnly = true)
    public LectureStatusDTO getStatus(UUID userId, UUID lectureId) {
        Lecture lecture = requireOwned(userId, lectureId);
        return LectureStatusDTO.of(lecture, (int) chunkRepository.countByLectureId(lectureId));
    }

    @Override
    @Transactional
    public LectureResponseDTO updateLecture(UUID userId, UUID lectureId, UpdateLectureRequestDTO request) {
        Lecture lecture = requireOwned(userId, lectureId);

        if (request.title() != null && !request.title().isBlank()) {
            lecture.setTitle(request.title().trim());
        }
        if (request.courseCode() != null) {
            lecture.setCourseCode(blankToNull(request.courseCode()));
        }

        boolean transcriptChanged = request.hasTranscriptEdit()
                && !request.fullTranscript().equals(lecture.getFullTranscript());

        if (transcriptChanged) {
            lecture.setFullTranscript(request.fullTranscript());
            lecture.setStatus(LectureStatus.PROCESSING);
            lecture.setStatusDetail("Re-indexing your edits…");
        }

        lectureRepository.save(lecture);

        // Queued after the write so the pipeline reads the committed transcript.
        if (transcriptChanged) {
            processor.reindexTranscript(lectureId, request.fullTranscript());
        }

        return LectureResponseDTO.of(lecture, List.of(),
                (int) chunkRepository.countByLectureId(lectureId));
    }

    @Override
    @Transactional
    public LectureResponseDTO retryProcessing(UUID userId, UUID lectureId) {
        Lecture lecture = requireOwned(userId, lectureId);

        if (lecture.getStatus() == LectureStatus.PROCESSING) {
            throw ApiException.badRequest("This lecture is already being processed.");
        }

        /*
         * An imported lecture retries from its transcript, not from its source.
         *
         * The uploaded PDF is not retained — extraction is deterministic and
         * re-running it would produce the identical text. What *can* fail is
         * everything after it: the embedding and note-structuring calls both
         * hit an external model. Since the transcript was saved before that
         * point, re-indexing recovers exactly the failure worth recovering
         * from, without keeping the original file around for it.
         */
        /*
         * A video is the one source that outlives its own import.
         *
         * The PDF is not retained and the audio may not be, but a URL costs
         * nothing to keep — so when the first read produced no transcript at
         * all, this can go back to the video itself rather than telling the
         * student to paste the same link again.
         */
        if (lecture.getSource() == Lecture.LectureSource.YOUTUBE
                && isBlank(lecture.getFullTranscript())
                && !isBlank(lecture.getSourceUrl())) {

            lecture.setStatus(LectureStatus.PENDING);
            lecture.setStatusDetail("Queued for another attempt…");
            lectureRepository.save(lecture);

            processor.processVideo(lectureId, lecture.getSourceUrl());

            log.info("Retrying video lecture {} for user {} from {}",
                    lectureId, userId, lecture.getSourceUrl());

            return LectureResponseDTO.of(lecture, List.of(),
                    (int) chunkRepository.countByLectureId(lectureId));
        }

        if (lecture.getSource() != Lecture.LectureSource.RECORDING) {
            String transcript = lecture.getFullTranscript();
            if (transcript == null || transcript.isBlank()) {
                throw ApiException.badRequest(
                        "Nothing was read from that file, so there is nothing to retry. "
                                + "Import it again.");
            }

            lecture.setStatus(LectureStatus.PENDING);
            lecture.setStatusDetail("Queued for another attempt…");
            lectureRepository.save(lecture);

            processor.reindexTranscript(lectureId, transcript);

            log.info("Retrying imported lecture {} for user {} from its stored transcript",
                    lectureId, userId);

            return LectureResponseDTO.of(lecture, List.of(),
                    (int) chunkRepository.countByLectureId(lectureId));
        }

        if (lecture.getAudioPath() == null) {
            throw ApiException.badRequest(
                    "The original recording is no longer available, so this lecture can't be retried.");
        }

        byte[] content = audioStorage.load(lecture.getAudioPath());
        if (content == null) {
            throw ApiException.badRequest(
                    "The original recording could not be read, so this lecture can't be retried.");
        }

        String storedFilename = java.nio.file.Paths.get(lecture.getAudioPath()).getFileName().toString();
        String mimeType = mimeResolver.resolve(storedFilename, null);

        lecture.setStatus(LectureStatus.PENDING);
        lecture.setStatusDetail("Queued for another attempt…");
        lectureRepository.save(lecture);

        // Hand off to the background pipeline, same as the original submission.
        processor.processAudio(lectureId, content, mimeType);

        log.info("Retrying lecture {} for user {} ({} bytes, {})",
                lectureId, userId, content.length, mimeType);

        return LectureResponseDTO.of(lecture, List.of(),
                (int) chunkRepository.countByLectureId(lectureId));
    }

    @Override
    @Transactional
    public void deleteLecture(UUID userId, UUID lectureId) {
        Lecture lecture = requireOwned(userId, lectureId);
        String audioPath = lecture.getAudioPath();

        // Chunks cascade at the database level, but removing them explicitly
        // keeps the persistence context honest.
        chunkRepository.deleteByLectureId(lectureId);
        lectureRepository.delete(lecture);

        audioStorage.delete(audioPath);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChunkMatchDTO> search(UUID userId, SearchRequestDTO request) {
        float[] queryVector = embeddingProvider.embedQuery(request.question());

        List<LectureChunkRepository.ChunkMatch> matches = chunkRepository.searchByEmbedding(
                userId,
                request.lectureId() == null ? null : request.lectureId().toString(),
                ChunkVectorWriter.toVectorLiteral(queryVector),
                request.effectiveTopK());

        return matches.stream().map(ChunkMatchDTO::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LibraryStats stats(UUID userId) {
        long total = lectureRepository.countByUserId(userId);
        long completed = lectureRepository.countByUserIdAndStatus(userId, LectureStatus.COMPLETED);
        long processing = lectureRepository.countByUserIdAndStatus(userId, LectureStatus.PROCESSING)
                + lectureRepository.countByUserIdAndStatus(userId, LectureStatus.PENDING);

        long chunks = lectureRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .mapToLong(lecture -> chunkRepository.countByLectureId(lecture.getId()))
                .sum();

        return new LibraryStats(total, completed, processing, chunks);
    }

    // ------------------------------------------------------------------

    private Lecture requireOwned(UUID userId, UUID lectureId) {
        return lectureRepository.findByIdAndUserId(lectureId, userId)
                // Deliberately 404 rather than 403: confirming that a lecture
                // exists but belongs to somebody else is itself a disclosure.
                .orElseThrow(() -> ApiException.notFound("Lecture not found."));
    }

    private static String defaultedTitle(String title) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        return "Lecture " + java.time.LocalDate.now();
    }

    /**
     * Falls back to the file's own name, which is nearly always more useful
     * than a date — students name lecture PDFs things like
     * "EE355-Lecture4-BJT.pdf", and that beats "Lecture 2026-07-26".
     */
    private static String documentTitle(String title, String fileName) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        if (fileName == null || fileName.isBlank()) {
            return "Document " + java.time.LocalDate.now();
        }

        String stem = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        // Separators become spaces so the title reads as words rather than as a
        // filename someone forgot to tidy up.
        stem = stem.replaceAll("[_-]+", " ").replaceAll("\\s{2,}", " ").trim();

        return stem.isBlank() ? "Document " + java.time.LocalDate.now() : stem;
    }

    /**
     * Checked by magic bytes, not by extension.
     *
     * <p>A browser or file picker will happily report {@code application/pdf}
     * for anything named {@code .pdf}, and the extension is chosen by whoever
     * saved the file. Every real PDF begins with {@code %PDF-}, so reading five
     * bytes settles it far more reliably than trusting either.
     */
    private static boolean looksLikePdf(MultipartFile file) {
        try (var stream = file.getInputStream()) {
            byte[] header = stream.readNBytes(5);
            return header.length == 5
                    && header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                    && header[3] == 'F' && header[4] == '-';
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
