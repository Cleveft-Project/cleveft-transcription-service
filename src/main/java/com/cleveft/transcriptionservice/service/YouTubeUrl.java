package com.cleveft.transcriptionservice.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A YouTube link the student pasted, reduced to the one thing that identifies it.
 *
 * <p>Students paste whatever the share sheet gave them: a mobile link, a link
 * with a tracking parameter, a link that resumes at 4:31, a link that happens to
 * sit inside a playlist. All of those point at the same video, and treating them
 * as different ones would import the same lecture twice and split its chunks
 * across two rows. Parsing down to the video id and rebuilding a canonical URL
 * is what makes "have I already imported this?" answerable.
 *
 * <p>Rejections are specific on purpose. "That link didn't work" tells a student
 * nothing; "that's a playlist, open the video itself" tells them what to do
 * next.
 */
public final class YouTubeUrl {

    /**
     * Eleven characters of base64url. Stable since YouTube's early days, and
     * checking it here means a typo is caught before it costs an API call.
     */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private static final Set<String> HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com", "youtu.be", "www.youtu.be");

    private final String videoId;

    private YouTubeUrl(String videoId) {
        this.videoId = videoId;
    }

    public String videoId() {
        return videoId;
    }

    /** The form stored on the lecture and handed to the model. */
    public String canonical() {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    /**
     * @throws InvalidVideoUrlException with a message written for the student
     */
    public static YouTubeUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidVideoUrlException("Paste a YouTube link to import.");
        }

        String trimmed = raw.trim();
        // Share sheets and chat apps hand over bare hostnames often enough that
        // demanding a scheme would reject links that are otherwise perfect.
        if (!trimmed.matches("(?i)^https?://.*")) {
            trimmed = "https://" + trimmed;
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidVideoUrlException("That does not look like a link. Paste the video's URL.");
        }

        String host = uri.getHost();
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new InvalidVideoUrlException(
                    "Cleveft imports YouTube videos. Paste a youtube.com or youtu.be link.");
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        String id = extractId(host.toLowerCase(Locale.ROOT), path, uri.getQuery());

        if (id == null) {
            // Worth separating: a playlist link is a thing students paste
            // constantly, and the fix is one tap rather than a different app.
            if (path.startsWith("/playlist") || queryValue(uri.getQuery(), "list") != null) {
                throw new InvalidVideoUrlException(
                        "That is a playlist. Open the video you want and paste its link instead.");
            }
            if (path.startsWith("/@") || path.startsWith("/c/") || path.startsWith("/channel")) {
                throw new InvalidVideoUrlException(
                        "That is a channel, not a video. Open the video and paste its link instead.");
            }
            throw new InvalidVideoUrlException(
                    "That link has no video in it. Use the Share button on the video itself.");
        }

        if (!VIDEO_ID.matcher(id).matches()) {
            throw new InvalidVideoUrlException("That video link looks incomplete. Copy it again.");
        }

        return new YouTubeUrl(id);
    }

    private static String extractId(String host, String path, String query) {
        if (host.endsWith("youtu.be")) {
            return firstSegment(path);
        }
        if (path.startsWith("/watch")) {
            return queryValue(query, "v");
        }
        // /shorts/ID, /embed/ID, /live/ID and /v/ID all carry the id in the same
        // position, so one branch covers every short-form and embed variant
        // rather than a list that needs extending each time YouTube adds one.
        if (path.startsWith("/shorts/") || path.startsWith("/embed/")
                || path.startsWith("/live/") || path.startsWith("/v/")) {
            return firstSegment(path.substring(path.indexOf('/', 1)));
        }
        return null;
    }

    private static String firstSegment(String path) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        String segment = slash < 0 ? stripped : stripped.substring(0, slash);
        return segment.isBlank() ? null : segment;
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(key)) {
                String value = pair.substring(equals + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /** Carries a message meant to be shown to the student verbatim. */
    public static class InvalidVideoUrlException extends RuntimeException {
        public InvalidVideoUrlException(String message) {
            super(message);
        }
    }
}
