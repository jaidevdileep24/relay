package com.dileep.relay.ai.impl;

import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
import com.dileep.relay.ai.FailureContext;

/**
 * Classification from the status code alone. No model, no network, no cost.
 *
 * <p>It exists for three reasons, in order of importance:
 *
 * <ol>
 *   <li>The feature works with {@code relay.ai.provider: none}. Nobody needs an
 *       LLM running to use this project.</li>
 *   <li>It is the fallback whenever a model is unavailable or unparseable, so a
 *       broken classifier degrades instead of breaking dispatch.</li>
 *   <li>It answers the cases a model cannot improve on. A connection timeout has
 *       no body to read - paying for tokens to be told it is a network error
 *       would be pure waste, and timeouts are the single most common failure in
 *       production.</li>
 * </ol>
 *
 * <p>Deliberately not a {@code @Component}: it is constructed by
 * {@link com.dileep.relay.ai.FailureClassifierConfig} as part of the stack. As a
 * bean it would be a second {@code FailureClassifier} in the context, and every
 * injection point would then depend on {@code @Primary} resolving the tie.
 *
 * <p>It is deliberately not the main event. Status codes lie: a receiver that
 * returns {@code 200 OK} with {@code {"error":"bad signature"}} is invisible
 * here and is exactly what the model is for.
 */
public class HeuristicFailureClassifier implements FailureClassifier {

	@Override
	public FailureClassification classify(FailureContext context) {
		Integer status = context.httpStatus();

		if (status == null) {
			return FailureClassification.of(FailureCategory.NETWORK,
					"no response received: " + context.errorMessage());
		}
		if (status == 401 || status == 403) {
			return FailureClassification.of(FailureCategory.AUTH, "HTTP " + status);
		}
		if (status == 429) {
			// No Retry-After here - the sender does not capture response headers,
			// so the delay stays null and the normal jittered backoff applies.
			return FailureClassification.of(FailureCategory.RATE_LIMITED, "HTTP 429");
		}
		if (status == 408 || status >= 500) {
			return FailureClassification.of(FailureCategory.SERVER_ERROR, "HTTP " + status);
		}
		if (status >= 400) {
			return FailureClassification.of(FailureCategory.CLIENT_ERROR, "HTTP " + status);
		}

		// 2xx never reaches a classifier, and 3xx is a misconfiguration we cannot
		// name from the code alone.
		return FailureClassification.unknown("unclassifiable status " + status);
	}
}
