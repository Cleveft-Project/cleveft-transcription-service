package com.cleveft.transcriptionservice.service;

import com.cleveft.transcriptionservice.ai.AiServiceException;
import com.cleveft.transcriptionservice.ai.GeminiClient;
import com.cleveft.transcriptionservice.ai.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw transcript into the structured artefacts the app renders:
 * chapter-style notes, extracted formulas and definitions, and a topic tag per
 * transcript chunk.
 *
 * <p>Failures here are non-fatal. A lecture with a transcript but no notes is
 * still fully queryable, so the caller degrades rather than failing the job.
 */
@Service
public class NoteStructuringService {

    private static final Logger log = LoggerFactory.getLogger(NoteStructuringService.class);

    /** Guard against sending an entire multi-hour transcript in one prompt. */
    private static final int MAX_TRANSCRIPT_CHARS = 120_000;

    private static final String INSTRUCTION = """
            You are an expert academic note-taker preparing study material from a university lecture transcript.

            Return ONLY a JSON object, with no markdown fences and no commentary, matching this shape:

            {
              "sections": [
                {"heading": "...", "summary": "...", "points": ["...", "..."]}
              ],
              "keyConcepts": [
                {"term": "...", "kind": "FORMULA|THEOREM|DEFINITION|EXAMPLE", "detail": "..."}
              ],
              "topics": ["...", "..."]
            }

            Rules:
            - "sections" must follow the lecture's own order. Between 3 and 10 sections.
            - "summary" is 1-2 sentences. "points" are 2-5 short factual bullets.
            - "keyConcepts" captures formulas, theorems, definitions and worked examples
              the lecturer actually stated. Omit the field entirely rather than
              inventing entries that were not said.
            - Mathematics is written as LaTeX, because a formula's layout carries as
              much meaning as its symbols: a fraction is a numerator above a
              denominator, and an integral's limits sit above and below the sign.
              Neither survives being flattened into a line of text.
              Wrap a formula that stands alone in $$ ... $$, and one that belongs
              inside a sentence in $ ... $. So:
                $$I^{(m)}(h) = \\frac{4^m I^{(m-1)}(h/2) - I^{(m-1)}(h)}{4^m - 1}$$
                $$V_{GS} = V_G - V_S$$
                $$\\int_a^b f(x)\\,dx \\approx \\frac{h}{2}\\left[f(x_0) + 2\\sum_{i=1}^{n-1} f(x_i) + f(x_n)\\right]$$
              Never write a fraction with a slash when it is a stacked fraction, and
              never spell a symbol out: use \\Omega, \\mu, \\pi, \\Delta, \\approx, \\times,
              \\pm, \\leq, \\infty rather than "ohm", "u", "pi", "approx" or "x".
              Units and quantities inside maths take a thin space: $510\\,\\text{k}\\Omega$,
              $-8\\,\\text{V}$, $3\\times10^{8}\\,\\text{m/s}$.
            - Ordinary prose stays ordinary prose. Do not wrap whole sentences in
              LaTeX, and do not use it for anything that is not mathematics.
            - Put each standalone formula on a line of its own within "detail", never
              inside a sentence. Separate lines with a newline character.
            - A worked example must read as a procedure rather than a paragraph.
              Lay its "detail" out as labelled lines, one step per line, each step
              showing the substitution it performs:
              Given: ...
              Find: ...
              ...working, a line at a time...
              Answer: ...
            - "topics" is a flat list of 3-12 short subject tags for this lecture,
              lowercase, each 1-4 words.
            - A topic must be examinable subject matter the lecturer actually taught:
              something a student could be asked a question about. Name the thing
              itself ("ohm's law", "tcp handshake", "photosynthesis"), never an
              abstract theme, skill or outcome. Reject anything of the form
              "process outcome", "future planning", "key considerations",
              "understanding concepts" — if a tag would fit almost any lecture in
              any subject, it is not a topic and must be left out.
            - Prefer fewer, sharper topics over padding the list to 12. If the
              transcript genuinely covers only two examinable subjects, return two.
            - Ground everything strictly in the transcript. Never add outside material.
            """;

    private final GeminiClient client;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public NoteStructuringService(GeminiClient client, GeminiProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public StructuredNotes structure(String transcript, String lectureTitle) {
        if (transcript == null || transcript.isBlank()) {
            return StructuredNotes.empty();
        }

        String trimmed = transcript.length() > MAX_TRANSCRIPT_CHARS
                ? transcript.substring(0, MAX_TRANSCRIPT_CHARS)
                : transcript;

        if (trimmed.length() < transcript.length()) {
            log.warn("Transcript for '{}' truncated from {} to {} chars for note structuring",
                    lectureTitle, transcript.length(), trimmed.length());
        }

        try {
            String raw = client.generateContent(
                    properties.notesModel(),
                    INSTRUCTION,
                    List.of(Map.of("text", "Lecture title: " + lectureTitle + "\n\nTranscript:\n" + trimmed)));

            return parse(raw);
        } catch (AiServiceException e) {
            log.warn("Note structuring failed for '{}': {}", lectureTitle, e.getMessage());
            return StructuredNotes.empty();
        }
    }

    private StructuredNotes parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(stripFences(raw));

            List<Map<String, Object>> sections = readObjectArray(root.path("sections"));
            List<Map<String, Object>> concepts = readObjectArray(root.path("keyConcepts"));

            List<String> topics = new ArrayList<>();
            for (JsonNode topic : root.path("topics")) {
                String value = topic.asText("").trim();
                if (!value.isEmpty()) {
                    topics.add(value);
                }
            }

            return new StructuredNotes(sections, concepts, topics);
        } catch (Exception e) {
            log.warn("Could not parse structured notes as JSON: {}", e.getMessage());
            return StructuredNotes.empty();
        }
    }

    private List<Map<String, Object>> readObjectArray(JsonNode array) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }

        for (JsonNode element : array) {
            Map<String, Object> entry = new LinkedHashMap<>();
            element.fields().forEachRemaining(field -> entry.put(field.getKey(), toPlainValue(field.getValue())));
            if (!entry.isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }

    private Object toPlainValue(JsonNode node) {
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(child -> values.add(toPlainValue(child)));
            return values;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    /**
     * Models wrap JSON in ```json fences often enough that stripping them is
     * cheaper than fighting the prompt.
     */
    private String stripFences(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    public record StructuredNotes(
            List<Map<String, Object>> sections,
            List<Map<String, Object>> keyConcepts,
            List<String> topics
    ) {
        public static StructuredNotes empty() {
            return new StructuredNotes(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return sections.isEmpty() && keyConcepts.isEmpty() && topics.isEmpty();
        }
    }
}
