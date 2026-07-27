package com.cleveft.transcriptionservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Keeps the original recording on disk after transcription.
 *
 * <p>Retaining the audio is what makes re-processing possible when the STT model
 * improves or a job fails midway; without it a failed lecture would mean asking
 * the student to attend the class again.
 *
 * <p>Local filesystem is the right call for a single-node deployment. Swapping
 * in object storage means reimplementing this one class.
 */
@Component
public class AudioStorage {

    private static final Logger log = LoggerFactory.getLogger(AudioStorage.class);

    private final Path root;
    private final boolean enabled;

    public AudioStorage(
            @Value("${cleveft.audio.storage-path:./data/audio}") String storagePath,
            @Value("${cleveft.audio.retain:true}") boolean enabled) {

        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
        this.enabled = enabled;

        if (enabled) {
            try {
                Files.createDirectories(root);
                log.info("Lecture audio will be retained under {}", root);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create audio storage directory " + root, e);
            }
        }
    }

    /**
     * @return the stored path, or null when retention is disabled or the write
     * failed — a storage problem must not fail an otherwise good transcription
     */
    public String store(UUID lectureId, byte[] content, String originalFilename) {
        if (!enabled) {
            return null;
        }

        try {
            String extension = extensionOf(originalFilename);
            Path target = root.resolve(lectureId + extension);
            Files.write(target, content);
            return target.toString();
        } catch (IOException e) {
            log.warn("Could not retain audio for lecture {}: {}", lectureId, e.getMessage());
            return null;
        }
    }

    /**
     * @return the retained bytes, or null if there is nothing retrievable —
     * a missing/unreadable file must not crash the caller, just tell it to
     * give up on retrying this lecture
     */
    public byte[] load(String storedPath) {
        if (storedPath == null) {
            return null;
        }
        try {
            Path path = Paths.get(storedPath).toAbsolutePath().normalize();
            // Same escape guard as delete(): never read outside the storage root.
            if (!path.startsWith(root)) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("Could not read retained audio at {}: {}", storedPath, e.getMessage());
            return null;
        }
    }

    public void delete(String storedPath) {
        if (storedPath == null) {
            return;
        }
        try {
            Path path = Paths.get(storedPath).toAbsolutePath().normalize();
            // Never let a stored path escape the storage root.
            if (path.startsWith(root)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not delete audio at {}: {}", storedPath, e.getMessage());
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return ".audio";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".audio";
        }
        String extension = filename.substring(dot).toLowerCase();
        // Only allow a conservative character set into a filesystem path.
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : ".audio";
    }
}
