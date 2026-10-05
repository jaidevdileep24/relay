package com.dileep.relay.ai;

import java.time.Duration;

/**
 * One classifier's verdict on one failed attempt.
 *
 * @param retryAfter how long the receiver asked us to wait, or null if it did
 *                   not say. Only ever set for {@link FailureCategory#RATE_LIMITED}.
 * @param reasoning  the classifier's own words. Persisted deliberately: when
 *                   this misclassifies in production it is the only record of
 *                   why, and the status code will not tell you.
 */
public record FailureClassification(
		FailureCategory category,
		boolean retryable,
		Duration retryAfter,
		String reasoning
) {

	/** Retryability follows from the category; callers never decide it separately. */
	public static FailureClassification of(FailureCategory category, String reasoning) {
		return new FailureClassification(category, category.isRetryable(), null, reasoning);
	}

	public static FailureClassification rateLimited(Duration retryAfter, String reasoning) {
		return new FailureClassification(FailureCategory.RATE_LIMITED, true, retryAfter, reasoning);
	}

	/** Whenever a classifier cannot answer: unavailable, unparseable, or unsure. */
	public static FailureClassification unknown(String reasoning) {
		return of(FailureCategory.UNKNOWN, reasoning);
	}

	public boolean hasRetryAfter() {
		return retryAfter != null;
	}
}
