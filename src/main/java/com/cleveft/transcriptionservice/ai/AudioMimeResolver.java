package com.cleveft.transcriptionservice.ai;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Works out what MIME type to declare for an uploaded recording.
 *
 * <p>Expo records to {@code .m4a} on both iOS and Android, and mobile clients
 * routinely send that as {@code application/octet-stream}. The provider rejects
 * an unrecognised type outright, so the file extension is the more reliable
 * signal and is preferred over whatever the client claimed.
 */
@Component
public class AudioMimeResolver {

    private static final Map<String, String> BY_EXTENSION = Map.of(
            "m4a", "audio/mp4",
            "mp4", "audio/mp4",
            "mp3", "audio/mp3",
            "wav", "audio/wav",
            "aac", "audio/aac",
            "ogg", "audio/ogg",
            "opus", "audio/ogg",
            "flac", "audio/flac",
            "aiff", "audio/aiff",
            "webm", "audio/webm");

    private static final String DEFAULT_MIME = "audio/mp4";

    public String resolve(String originalFilename, String declaredContentType) {
        String extension = extensionOf(originalFilename);
        String fromExtension = BY_EXTENSION.get(extension);
        if (fromExtension != null) {
            return fromExtension;
        }

        if (declaredContentType != null
                && declaredContentType.startsWith("audio/")
                && BY_EXTENSION.containsValue(declaredContentType)) {
            return declaredContentType;
        }

        return DEFAULT_MIME;
    }

    public boolean isSupported(String originalFilename, String declaredContentType) {
        return BY_EXTENSION.containsKey(extensionOf(originalFilename))
                || (declaredContentType != null && declaredContentType.startsWith("audio/"));
    }

    public String supportedExtensions() {
        return String.join(", ", BY_EXTENSION.keySet().stream().sorted().toList());
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
