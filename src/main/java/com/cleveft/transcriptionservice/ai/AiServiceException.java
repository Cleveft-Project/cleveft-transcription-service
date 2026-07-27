package com.cleveft.transcriptionservice.ai;

/**
 * Any failure talking to the upstream AI provider. Distinct from a bug in our
 * own code so the processing job can mark a lecture FAILED with a message the
 * student can actually act on.
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
