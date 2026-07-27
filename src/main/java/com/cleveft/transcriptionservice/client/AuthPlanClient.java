package com.cleveft.transcriptionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Reads a student's subscription tier from the auth service, which owns it.
 *
 * <p>The plan is fetched per upload rather than read from a JWT claim: a token
 * issued before an upgrade would still say FREE, so a student who just paid
 * would keep hitting the cap until their access token expired. An upload is
 * already a slow, heavyweight request — one more short internal call is not
 * what makes it slow.
 */
@Component
public class AuthPlanClient {

    private static final Logger log = LoggerFactory.getLogger(AuthPlanClient.class);

    /**
     * Returned when auth cannot be reached. A null limit means unlimited, so
     * this waves the upload through — see {@link #planFor} for why.
     */
    private static final PlanSummary UNKNOWN = new PlanSummary(null, "UNKNOWN", null);

    private final RestClient restClient;

    public AuthPlanClient(@Value("${cleveft.auth-service.url:http://localhost:8084}") String baseUrl,
                          @Value("${cleveft.auth-service.timeout-ms:5000}") int timeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * @return the caller's tier and allowance, or an unlimited fallback when the
     * auth service is unreachable.
     *
     * <p>Failing open is deliberate. The alternative — refusing the upload —
     * turns an auth-service outage into "Cleveft cannot record lectures at all",
     * and loses a lecture that is happening right now and cannot be recaptured.
     * A handful of uploads slipping past the cap during an outage is the far
     * cheaper failure.
     */
    public PlanSummary planFor(UUID userId) {
        try {
            PlanSummary summary = restClient.get()
                    .uri("/internal/plan/{userId}", userId)
                    .retrieve()
                    .body(PlanSummary.class);

            return summary == null ? UNKNOWN : summary;

        } catch (RestClientException e) {
            log.warn("Could not read plan for {} ({}). Allowing the upload.", userId, e.getMessage());
            return UNKNOWN;
        }
    }

    /**
     * @param monthlyRecordingLimit null means unlimited
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanSummary(UUID userId, String plan, Integer monthlyRecordingLimit) {

        public boolean isUnlimited() {
            return monthlyRecordingLimit == null;
        }

        public boolean isPro() {
            return "PRO".equalsIgnoreCase(plan);
        }
    }
}
