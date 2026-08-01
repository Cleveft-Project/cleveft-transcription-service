package com.cleveft.transcriptionservice.ai;

/**
 * Turns a video the student linked to into study text.
 *
 * <p>The counterpart to {@link SttProvider}, kept separate for the same reason
 * that PDF extraction is: what arrives is different, what leaves is the same.
 * Everything downstream of this interface — chunking, embedding, note
 * structuring, citations — cannot tell which of the three produced its input.
 */
public interface VideoProvider {

    /**
     * @param url a canonical watch URL
     * @return the video's teaching content as continuous prose
     * @throws AiServiceException if the provider cannot reach or read the video
     */
    String describe(String url);

    /** Identifies the provider and model in logs and status detail. */
    String name();
}
