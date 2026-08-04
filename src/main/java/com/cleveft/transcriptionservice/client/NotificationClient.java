package com.cleveft.transcriptionservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the auth service to notify a student.
 *
 * <p>This service says what happened; auth decides whether it becomes a push.
 * Preferences, quiet hours and the student's timezone all live there, with the
 * student. Duplicating those rules here would mean two places to change when one
 * of them moves, and one of them would be missed.
 *
 * <p>Nothing here throws or blocks meaningfully. A lecture that transcribed
 * successfully has succeeded whether or not the phone buzzes about it, and an
 * exception thrown from a notification would roll back the very thing being
 * announced.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestClient restClient;

    public NotificationClient(@Value("${cleveft.auth-service.url:http://localhost:8084}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        // Auth answers this as soon as it has queued the send, so it does not
        // wait on Expo. Short is right.
        factory.setReadTimeout(5_000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public void notify(UUID userId, String category, String title, String body, Map<String, Object> data) {
        if (userId == null) {
            return;
        }
        try {
            restClient.post()
                    .uri("/internal/notify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "userIds", List.of(userId),
                            "category", category,
                            "title", title,
                            "body", body,
                            "data", data == null ? Map.of() : data))
                    .retrieve()
                    .toBodilessEntity();

        } catch (Exception e) {
            log.warn("Could not request {} notification for {}: {}", category, userId, e.getMessage());
        }
    }
}
