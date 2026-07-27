package com.cleveft.transcriptionservice.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Pulls readable text out of an uploaded PDF.
 *
 * <p>This is the document equivalent of speech-to-text: it takes a file the
 * student handed over and returns the words in it, after which the lecture is
 * indistinguishable from a recording as far as the rest of the pipeline is
 * concerned.
 */
@Service
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    /**
     * Below this many characters per page, a PDF is almost certainly scanned.
     *
     * <p>A page of lecture slides carries a few hundred characters and a page of
     * prose well over a thousand. A scanned page carries none — but rarely
     * exactly zero, because scanners and exporters leave stray artefacts, page
     * numbers and OCR-lite headers behind. Testing for "roughly empty" rather
     * than "empty" is what makes the check actually fire.
     */
    private static final int MIN_CHARS_PER_PAGE = 40;

    /** Beyond this, structuring the notes would blow the model's context anyway. */
    private static final int MAX_PAGES = 300;

    /**
     * @return the document's text, whitespace-normalised
     * @throws DocumentExtractionException if the file is not a readable PDF, or
     *                                     carries no text layer
     */
    public String extract(byte[] pdf, String fileName) {
        if (pdf == null || pdf.length == 0) {
            throw new DocumentExtractionException("That file was empty.");
        }

        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted()) {
                throw new DocumentExtractionException(
                        "That PDF is password-protected, so Cleveft cannot read it. "
                                + "Save an unprotected copy and upload that instead.");
            }

            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new DocumentExtractionException("That PDF has no pages.");
            }
            if (pages > MAX_PAGES) {
                throw new DocumentExtractionException(
                        "That PDF is " + pages + " pages. Cleveft handles up to " + MAX_PAGES
                                + " — split it and upload the part you are revising.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            // Reading order rather than the order objects happen to appear in the
            // file. Without this, multi-column layouts — which is most lecture
            // handouts — come out interleaved line by line and are nonsense to
            // both the note structurer and the student reading the transcript.
            stripper.setSortByPosition(true);

            String raw = stripper.getText(document);
            String text = normalise(raw);

            if (text.length() < (long) pages * MIN_CHARS_PER_PAGE) {
                log.info("PDF {} looks scanned: {} chars across {} pages", fileName, text.length(), pages);
                throw new DocumentExtractionException(
                        "That PDF looks like scanned images rather than text, so there are no words "
                                + "for Cleveft to read. Try a PDF exported from slides or a document, "
                                + "or record the lecture instead.");
            }

            log.info("Extracted {} characters from {} ({} pages)", text.length(), fileName, pages);
            return text;

        } catch (DocumentExtractionException e) {
            throw e;
        } catch (IOException e) {
            // PDFBox throws IOException for anything it cannot parse, including a
            // file that is not a PDF at all despite its extension.
            log.warn("Could not read PDF {}: {}", fileName, e.getMessage());
            throw new DocumentExtractionException(
                    "Cleveft could not read that PDF. It may be corrupted or not really a PDF.");
        }
    }

    /**
     * Collapses the ragged whitespace PDF extraction produces.
     *
     * <p>Text stripped from a PDF arrives with a line break at the end of every
     * visual line, so a single sentence spans four lines. Left alone the chunker
     * would split mid-sentence and the embeddings would be measurably worse.
     * Blank lines are preserved because those are real paragraph breaks.
     */
    private String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace(' ', ' ')
                // A hyphen at end of line is a word broken across the line break.
                .replaceAll("-\\r?\\n\\s*", "")
                // Two or more newlines are a paragraph; keep exactly one blank line.
                .replaceAll("\\r?\\n\\s*\\r?\\n+", "\n\n")
                // A lone newline is line wrapping, not a sentence ending.
                .replaceAll("(?<!\\n)\\r?\\n(?!\\n)", " ")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    /** Thrown when a document cannot yield usable text. Messages are shown to the student. */
    public static class DocumentExtractionException extends RuntimeException {
        public DocumentExtractionException(String message) {
            super(message);
        }
    }
}
