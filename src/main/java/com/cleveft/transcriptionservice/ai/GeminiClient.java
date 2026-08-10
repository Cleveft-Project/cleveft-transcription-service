package com.cleveft.transcriptionservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin REST binding over the Google GenAI HTTP API.
 *
 * <p>Written against the raw endpoints rather than a vendor SDK so that the
 * request shapes stay visible and the provider stays swappable — everything
 * above this class talks in terms of {@link SttProvider} and
 * {@link EmbeddingProvider}, never Gemini.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String API_KEY_HEADER = "x-goog-api-key";

    /**
     * Low, because transcription and note structuring both want the model to
     * follow the source rather than invent around it.
     */
    private static final double DEFAULT_TEMPERATURE = 0.2;

    /**
     * Used only after a RECITATION block. Higher variance makes the sampled
     * output less likely to match memorised text closely enough to be refused,
     * at the cost of a slightly looser transcript — which is strictly better
     * than no transcript at all.
     */
    private static final double RECITATION_RETRY_TEMPERATURE = 0.75;

    /** Attempts after the first, for refusals that pass on their own. */
    private static final int MAX_TRANSIENT_RETRIES = 3;
    /** Used only when Google does not say how long to wait. */
    private static final long BASE_TRANSIENT_WAIT_MS = 2_000;
    /** However long it asks for, a worker does not vanish for longer than this. */
    private static final long MAX_TRANSIENT_WAIT_MS = 65_000;

    /** `"retryDelay": "48.05s"` inside the RetryInfo detail of an error body. */
    private static final Pattern RETRY_DELAY =
            Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"");

    /** Default advice, written for the case that trips this most often. */
    private static final String RECORDING_BLOCKED_MESSAGE =
            "Google blocked this recording, because the audio closely matches text it "
            + "recognises from published material. This usually means the speaker was "
            + "reading aloud from a book, paper or slide deck. Re-recording in the "
            + "lecturer's own words normally works — or import the material as a PDF "
            + "instead, which does not go through speech-to-text.";

    /**
     * Google discarded the candidate because it resembled memorised training
     * data. Internal to this class: callers see either a transcript or a
     * plain-language {@link AiServiceException}, never this.
     */
    private static final class RecitationBlockedException extends RuntimeException {
        private RecitationBlockedException(String message) {
            super(message);
        }
    }

    private final GeminiProperties properties;
    private final RestClient restClient;

    public GeminiClient(GeminiProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) java.time.Duration.ofSeconds(20).toMillis());
        factory.setReadTimeout((int) properties.requestTimeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    private void requireApiKey() {
        if (!properties.isConfigured()) {
            throw new AiServiceException(
                    "GOOGLE_API_KEY is not configured. Set it before uploading a lecture.");
        }
    }

    // ------------------------------------------------------------------
    //  Text generation
    // ------------------------------------------------------------------

    /**
     * Calls {@code models:generateContent} and returns the concatenated text of
     * the first candidate.
     *
     * @param parts already-built {@code parts} entries — text, inline_data or
     *              file_data, in the order the model should see them
     */
    public String generateContent(String model, String systemInstruction, List<Map<String, Object>> parts) {
        return generateContent(model, systemInstruction, parts, RECORDING_BLOCKED_MESSAGE);
    }

    /**
     * As above, but with wording for a source that is not a recording.
     *
     * <p>The advice that fixes a RECITATION block depends entirely on what was
     * submitted — "re-record in the lecturer's own words" is useless to someone
     * who pasted a link. The retry is identical either way; only the sentence
     * the student reads at the end of it differs.
     *
     * @param blockedMessage shown verbatim once both attempts have been refused
     */
    public String generateContent(String model,
                                  String systemInstruction,
                                  List<Map<String, Object>> parts,
                                  String blockedMessage) {
        try {
            return generateContent(model, systemInstruction, parts, DEFAULT_TEMPERATURE);
        } catch (RecitationBlockedException blocked) {
            // RECITATION is not deterministic: the filter compares the sampled
            // output against memorised text, so a different sample often passes
            // where the first did not. Retrying with more variance is the
            // cheapest fix available, and costs one extra call only in the rare
            // case that trips it.
            log.warn("Model {} blocked its output as RECITATION; retrying at temperature {}",
                    model, RECITATION_RETRY_TEMPERATURE);
            try {
                return generateContent(model, systemInstruction, parts, RECITATION_RETRY_TEMPERATURE);
            } catch (RecitationBlockedException stillBlocked) {
                throw new AiServiceException(blockedMessage);
            }
        }
    }

    private String generateContent(String model,
                                   String systemInstruction,
                                   List<Map<String, Object>> parts,
                                   double temperature) {
        requireApiKey();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("role", "user", "parts", parts)));
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        body.put("generationConfig", Map.of(
                "temperature", temperature,
                "maxOutputTokens", 65536));

        JsonNode response = post("/v1beta/models/" + model + ":generateContent", body);
        return extractText(response, model);
    }

    private String extractText(JsonNode response, String model) {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = response.path("promptFeedback").path("blockReason").asText("");
            throw new AiServiceException(blockReason.isBlank()
                    ? "The AI model returned no candidates."
                    : "The AI model refused the request (" + blockReason + ").");
        }

        JsonNode first = candidates.get(0);
        String finishReason = first.path("finishReason").asText("");

        StringBuilder text = new StringBuilder();
        for (JsonNode part : first.path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
        }

        if (text.isEmpty()) {
            // Distinguished from every other empty response because it is the
            // one worth retrying, and the one a student can actually act on.
            if ("RECITATION".equals(finishReason)) {
                throw new RecitationBlockedException("Candidate withheld as recitation by " + model);
            }
            throw new AiServiceException("The AI model returned an empty response (finishReason="
                    + finishReason + ").");
        }
        if ("MAX_TOKENS".equals(finishReason)) {
            log.warn("Model {} hit the output token cap; result is truncated", model);
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    //  Embeddings
    // ------------------------------------------------------------------

    /**
     * Embeds a batch of texts in a single round trip.
     *
     * @param taskType {@code RETRIEVAL_DOCUMENT} when indexing, or
     *                 {@code RETRIEVAL_QUERY} when embedding a question — the
     *                 two are not interchangeable and mixing them measurably
     *                 degrades retrieval quality
     * @return one vector per input, in the same order
     */
    public List<float[]> embed(List<String> texts, String taskType) {
        requireApiKey();
        if (texts.isEmpty()) {
            return List.of();
        }

        String model = properties.embeddingModel();
        String qualifiedModel = "models/" + model;

        List<Map<String, Object>> requests = texts.stream()
                .map(text -> Map.<String, Object>of(
                        "model", qualifiedModel,
                        "content", Map.of("parts", List.of(Map.of("text", text))),
                        "outputDimensionality", properties.dimensions(),
                        "taskType", taskType))
                .toList();

        JsonNode response = post("/v1beta/" + qualifiedModel + ":batchEmbedContents",
                Map.of("requests", requests));

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (JsonNode embedding : response.path("embeddings")) {
            JsonNode values = embedding.path("values");
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            if (vector.length != properties.dimensions()) {
                throw new AiServiceException("Embedding model returned " + vector.length
                        + " dimensions but the vector column expects " + properties.dimensions() + ".");
            }
            vectors.add(vector);
        }

        if (vectors.size() != texts.size()) {
            throw new AiServiceException("Embedding provider returned " + vectors.size()
                    + " vectors for " + texts.size() + " inputs.");
        }
        return vectors;
    }

    // ------------------------------------------------------------------
    //  Files API — for audio too large to inline
    // ------------------------------------------------------------------

    /**
     * Uploads bytes via the resumable Files API.
     *
     * @return the {@code file_uri} to reference in a later generateContent call
     */
    public String uploadFile(byte[] content, String mimeType, String displayName) {
        requireApiKey();

        String uploadUrl = restClient.post()
                .uri("/upload/v1beta/files")
                .header(API_KEY_HEADER, properties.apiKey())
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", String.valueOf(content.length))
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("file", Map.of("display_name", displayName)))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new AiServiceException("Failed to start upload: HTTP "
                                + response.getStatusCode().value());
                    }
                    return response.getHeaders().getFirst("x-goog-upload-url");
                });

        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new AiServiceException("Upload session did not return an upload URL.");
        }

        JsonNode uploaded = restClient.post()
                .uri(URI.create(uploadUrl))
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content)
                .retrieve()
                .body(JsonNode.class);

        if (uploaded == null) {
            throw new AiServiceException("Upload finalize returned no body.");
        }

        JsonNode file = uploaded.path("file");
        String uri = file.path("uri").asText(null);
        if (uri == null || uri.isBlank()) {
            throw new AiServiceException("Uploaded file has no URI.");
        }

        awaitActive(file.path("name").asText(null), file.path("state").asText("PROCESSING"));
        return uri;
    }

    /**
     * Audio uploads sit in PROCESSING for a few seconds. Referencing the file
     * before it reaches ACTIVE fails the generateContent call outright.
     */
    private void awaitActive(String fileName, String initialState) {
        if ("ACTIVE".equals(initialState) || fileName == null) {
            return;
        }

        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiServiceException("Interrupted while waiting for the upload to be processed.");
            }

            JsonNode status = restClient.get()
                    .uri("/v1beta/" + fileName)
                    .header(API_KEY_HEADER, properties.apiKey())
                    .retrieve()
                    .body(JsonNode.class);

            String state = status == null ? "" : status.path("state").asText("");
            if ("ACTIVE".equals(state)) {
                return;
            }
            if ("FAILED".equals(state)) {
                throw new AiServiceException("The provider could not process this audio file.");
            }
        }
        throw new AiServiceException("Timed out waiting for the audio upload to become available.");
    }

    // ------------------------------------------------------------------

    /**
     * Every call to Google goes through here, so this is where waiting lives.
     *
     * <p>Two of its refusals are not failures at all. A 503 means the model is
     * momentarily oversubscribed, and a 429 means a per-minute allowance is
     * spent — both pass on their own, and both arrive with the answer to "how
     * long", which Google puts in a RetryInfo detail as `retryDelay`. Reading
     * it and waiting is strictly better than guessing, and far better than what
     * happened before, which was to hand a student an exclamation mark and a
     * button that did exactly what this loop does, only manually.
     *
     * <p>Nothing else is retried. A 400 is a malformed request and will be
     * malformed again; a 403 is a bad key. Repeating either wastes the
     * allowance that caused the problem in the first place.
     */
    private JsonNode post(String path, Object body) {
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri(path)
                        .header(API_KEY_HEADER, properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);

                if (response == null) {
                    throw new AiServiceException("Empty response from the AI provider.");
                }
                return response;
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                boolean passesOnItsOwn = status == 429 || status == 503;

                if (passesOnItsOwn && attempt < MAX_TRANSIENT_RETRIES) {
                    long waitMs = retryDelayMillis(e.getResponseBodyAsString(), attempt);
                    log.warn("Google GenAI returned {} for {}; waiting {}ms then retrying ({}/{})",
                            status, path, waitMs, attempt + 1, MAX_TRANSIENT_RETRIES);
                    sleepQuietly(waitMs);
                    continue;
                }

                log.error("Google GenAI call to {} failed: {}", path, e.getResponseBodyAsString());
                throw new AiServiceException(describeProviderError(e), e);
            } catch (RestClientException e) {
                log.error("Google GenAI call to {} failed", path, e);
                throw new AiServiceException("Could not reach the AI provider. Please try again.", e);
            }
        }
    }

    /**
     * How long Google asked us to wait, or a doubling fallback if it did not say.
     *
     * <p>Capped, because the delay on an exhausted daily allowance can be hours
     * and a worker thread must not disappear for one.
     */
    private static long retryDelayMillis(String responseBody, int attempt) {
        Matcher matcher = RETRY_DELAY.matcher(responseBody == null ? "" : responseBody);
        if (matcher.find()) {
            try {
                long asked = (long) (Double.parseDouble(matcher.group(1)) * 1000);
                // A second's grace: coming back on the exact tick tends to be
                // refused again by whichever counter is being reset.
                return Math.min(MAX_TRANSIENT_WAIT_MS, asked + 1000);
            } catch (NumberFormatException ignored) {
                // Fall through to the backoff below.
            }
        }
        return Math.min(MAX_TRANSIENT_WAIT_MS, BASE_TRANSIENT_WAIT_MS * (1L << attempt));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting to retry the AI provider.", e);
        }
    }

    /**
     * Turns a provider error body into one readable sentence.
     *
     * <p>Left raw, these surface in the app as a wall of escaped JSON. The
     * provider's own {@code error.message} is the only part a person can act on.
     */
    private String describeProviderError(HttpStatusCodeException e) {
        String message = null;
        try {
            message = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(e.getResponseBodyAsString())
                    .path("error").path("message").asText(null);
        } catch (Exception ignored) {
            // Non-JSON body; fall through to the status-based wording.
        }

        if (message != null && !message.isBlank()) {
            return "The AI provider rejected the request: " + message;
        }
        return "The AI provider returned HTTP " + e.getStatusCode().value() + ".";
    }
}
