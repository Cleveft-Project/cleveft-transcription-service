package com.cleveft.transcriptionservice.service;

import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a transcript into retrieval-sized pieces.
 *
 * <p>Chunks are cut on sentence boundaries, not fixed character offsets, so a
 * definition or formula never gets sliced in half — a chunk that ends mid-clause
 * embeds badly and retrieves worse. Consecutive chunks overlap by one or two
 * sentences so a concept explained across a boundary is still reachable from
 * either side.
 */
@Component
public class TranscriptChunker {

    private static final int TARGET_CHARS = 1_200;
    private static final int OVERLAP_CHARS = 200;
    private static final int MIN_CHUNK_CHARS = 120;

    /**
     * @param text            the full transcript
     * @param durationSeconds recording length, used to interpolate per-chunk
     *                        timestamps; may be null
     */
    public List<Chunk> chunk(String text, Integer durationSeconds) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = splitIntoSentences(text.trim());
        List<String> rawChunks = groupSentences(sentences);

        int totalChars = rawChunks.stream().mapToInt(String::length).sum();
        List<Chunk> chunks = new ArrayList<>(rawChunks.size());

        int consumedChars = 0;
        for (int index = 0; index < rawChunks.size(); index++) {
            String content = rawChunks.get(index);

            Double start = null;
            Double end = null;
            if (durationSeconds != null && durationSeconds > 0 && totalChars > 0) {
                // Speech rate is roughly constant across a lecture, so character
                // position is a serviceable proxy for elapsed time. It is an
                // estimate and is presented as one.
                start = round((double) consumedChars / totalChars * durationSeconds);
                end = round((double) (consumedChars + content.length()) / totalChars * durationSeconds);
            }

            chunks.add(new Chunk(index, content, start, end));
            consumedChars += content.length();
        }

        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iterator.setText(text);

        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private List<String> groupSentences(List<String> sentences) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            if (!current.isEmpty() && current.length() + sentence.length() > TARGET_CHARS) {
                chunks.add(current.toString().trim());
                current = new StringBuilder(tailOf(current.toString()));
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
        }

        if (!current.isEmpty()) {
            String tail = current.toString().trim();
            // Fold a stub tail back into the previous chunk rather than leaving
            // a near-empty chunk that pollutes retrieval.
            if (tail.length() < MIN_CHUNK_CHARS && !chunks.isEmpty()) {
                chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + " " + tail);
            } else {
                chunks.add(tail);
            }
        }

        return chunks;
    }

    /** Last {@value #OVERLAP_CHARS} characters, rounded out to a word boundary. */
    private String tailOf(String chunk) {
        if (chunk.length() <= OVERLAP_CHARS) {
            return chunk;
        }
        String tail = chunk.substring(chunk.length() - OVERLAP_CHARS);
        int firstSpace = tail.indexOf(' ');
        return firstSpace < 0 ? "" : tail.substring(firstSpace + 1);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record Chunk(int index, String content, Double startTime, Double endTime) {
    }
}
