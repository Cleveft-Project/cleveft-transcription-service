package com.cleveft.transcriptionservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A YouTube link the student wants imported.
 *
 * <p>JSON rather than multipart, unlike the other two import routes, because
 * there is no file — sending a URL as a form part to match the shape of the
 * others would be ceremony for its own sake.
 *
 * @param url              the link, in any form YouTube hands out
 * @param title            optional; the video's own title is used when omitted
 * @param courseCode       optional course this belongs to
 * @param relatedLectureId the lecture this video was found to help explain.
 *                         Supplied automatically when the import starts from a
 *                         lecture screen, which is the usual way in — students
 *                         mostly look up a video because a particular class did
 *                         not land, and asking them to restate that afterwards
 *                         is a question the app can answer itself.
 */
public record ImportVideoRequestDTO(
        @NotBlank(message = "Paste a YouTube link to import.")
        @Size(max = 1000, message = "That link is too long to be a video URL.")
        String url,

        @Size(max = 500, message = "That title is too long.")
        String title,

        @Size(max = 255, message = "That course code is too long.")
        String courseCode,

        UUID relatedLectureId
) {
}
