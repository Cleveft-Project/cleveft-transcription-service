package com.cleveft.transcriptionservice.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Speech-to-text via Gemini's multimodal {@code generateContent}.
 *
 * <p>Small files are sent inline. Anything above the inline ceiling is pushed
 * through the resumable Files API first — a one-hour lecture is far too large
 * to base64 into a JSON body.
 */
@Component
@ConditionalOnProperty(name = "cleveft.stt.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiSttProvider implements SttProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiSttProvider.class);

    private static final String INSTRUCTION = """
            You are a professional academic transcriptionist working on a university lecture recording.

            Transcribe the audio verbatim into clean, readable text.

            Rules:
            - Output ONLY the transcript. No preamble, no commentary, no markdown fences.
            - Preserve the lecturer's actual wording. Do not summarise, shorten or embellish.
            - Remove filler sounds ("uh", "um", false starts) and repair obvious stutters.
            - Add sentence punctuation and paragraph breaks where the speaker pauses or changes topic.
            - When more than one person speaks, prefix turns with "Lecturer:" or "Student:".
            - Write formulas, symbols and technical terms out accurately; keep units attached to values.
            - If a passage is inaudible, write [inaudible] rather than guessing.
            """;

    private final GeminiClient client;
    private final GeminiProperties properties;

    public GeminiSttProvider(GeminiClient client, GeminiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String transcribe(byte[] audio, String mimeType, String language) {
        if (audio == null || audio.length == 0) {
            throw new AiServiceException("The uploaded audio file is empty.");
        }

        Map<String, Object> audioPart;
        if (audio.length <= properties.inlineLimitBytes()) {
            audioPart = Map.of("inline_data", Map.of(
                    "mime_type", mimeType,
                    "data", Base64.getEncoder().encodeToString(audio)));
        } else {
            log.info("Audio is {} bytes; routing through the Files API", audio.length);
            String fileUri = client.uploadFile(audio, mimeType, "cleveft-lecture");
            audioPart = Map.of("file_data", Map.of(
                    "mime_type", mimeType,
                    "file_uri", fileUri));
        }

        String prompt = (language == null || language.isBlank())
                ? "Transcribe this lecture recording."
                : "Transcribe this lecture recording. The primary language is " + language + ".";

        String transcript = client.generateContent(
                properties.sttModel(),
                INSTRUCTION,
                List.of(Map.of("text", prompt), audioPart));

        String cleaned = transcript.trim();
        if (cleaned.isEmpty()) {
            throw new AiServiceException("No speech could be recognised in this recording.");
        }
        return cleaned;
    }

    @Override
    public String name() {
        return "gemini:" + properties.sttModel();
    }
}
