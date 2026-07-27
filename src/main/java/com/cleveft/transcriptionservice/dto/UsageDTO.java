package com.cleveft.transcriptionservice.dto;

import java.time.OffsetDateTime;

/**
 * What the student has used against their allowance this period.
 *
 * <p>{@code limit} and {@code remaining} are null on an unlimited tier rather
 * than a sentinel like -1, so a client that forgets to special-case it renders
 * nothing instead of "-1 recordings left".
 *
 * @param plan            FREE or PRO
 * @param used            recordings started this period
 * @param limit           allowance, or null when unlimited
 * @param remaining       allowance left, or null when unlimited
 * @param periodResetsAt  when the counter goes back to zero
 */
public record UsageDTO(
        String plan,
        int used,
        Integer limit,
        Integer remaining,
        OffsetDateTime periodResetsAt
) {
}
