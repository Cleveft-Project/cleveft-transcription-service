package com.cleveft.transcriptionservice.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Reads a YouTube video through Gemini's native video understanding.
 *
 * <p>The URL is passed straight to the model as a {@code file_data} part. Google
 * fetches the video itself, which is why this needs no downloader, no ffmpeg and
 * no extra container dependencies — and why it stays on the right side of
 * YouTube's terms, unlike scraping the audio down and transcribing it.
 */
@Component
@ConditionalOnProperty(name = "cleveft.video.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiVideoProvider implements VideoProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiVideoProvider.class);

    /**
     * Deliberately not "transcribe verbatim".
     *
     * <p>Asking a model to reproduce the exact words of a published video is the
     * request most likely to come back blocked as RECITATION — the filter exists
     * precisely to stop models reciting copyrighted material, and a popular
     * lecture video is exactly what it recognises. Asking instead for a faithful
     * written account of what the video teaches sidesteps that entirely.
     *
     * <p>The trade-off is real and worth stating: for a recording, the transcript
     * is the lecturer's own words and citations quote them. For a video, this is
     * a close retelling. That is the right call here — a student imports a video
     * to understand a topic, not to be examined on the presenter's phrasing.
     */
    private static final String INSTRUCTION = """
            You are an academic note-taker helping a university student study from a video.

            Write a complete, detailed account of everything the video teaches, in your own words.

            Rules:
            - Output ONLY the account. No preamble, no commentary, no markdown fences.
            - Follow the video's own order, so the account can be read alongside it.
            - Do not reproduce long passages of the narration word for word. Restate the ideas.
            - Keep every definition, formula, derivation, worked example and stated rule. These are
              the parts a student is examined on, and losing them makes the import worthless.
            - Write formulas and symbols out accurately, and keep units attached to values.
            - Describe what is shown on screen when a diagram or worked step carries the meaning.
            - Cover the whole video, including the closing summary. Do not stop early.
            - Ignore sponsorships, subscribe requests, channel promotion and anything else that is
              not teaching material.
            """;

    private final GeminiClient client;
    private final GeminiProperties properties;

    public GeminiVideoProvider(GeminiClient client, GeminiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String describe(String url) {
        if (url == null || url.isBlank()) {
            throw new AiServiceException("No video link was given.");
        }

        log.info("Reading video {} with {}", url, properties.videoModel());

        // No mime_type: the provider resolves a YouTube URI itself, and sending
        // a guessed one is rejected.
        Map<String, Object> videoPart = Map.of("file_data", Map.of("file_uri", url));

        String account = client.generateContent(
                properties.videoModel(),
                INSTRUCTION,
                List.of(Map.of("text", "Write study notes covering everything this video teaches."),
                        videoPart),
                "Google would not summarise that video, because its content closely matches "
                        + "material it recognises as published. Try a different video on the same "
                        + "topic — or if your lecturer shared slides on it, import those as a PDF.");

        String cleaned = account.trim();
        if (cleaned.isEmpty()) {
            throw new AiServiceException(
                    "Nothing could be read from that video. It may be private, age-restricted "
                            + "or a live stream.");
        }
        return cleaned;
    }

    @Override
    public String name() {
        return "gemini:" + properties.videoModel();
    }
}
