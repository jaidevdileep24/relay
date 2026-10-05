package com.dileep.relay.ai.impl;

import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
import com.dileep.relay.ai.FailureContext;

/**
 * Routes each failure to the cheapest classifier that can actually answer it.
 *
 * <p>Two rules:
 *
 * <ul>
 *   <li><b>No body, no model.</b> A connect timeout carries no text, so there is
 *       nothing for a language model to read and nothing it could add. Timeouts
 *       are also the most common failure in production, so short-circuiting them
 *       removes most of the traffic before any spend happens.</li>
 *   <li><b>Model first when there is a body, heuristic as the net.</b> This is
 *       the whole premise of the phase: status codes lie. A receiver returning
 *       {@code 200 OK} with {@code {"error":"invalid signature"}}, or a 500 that
 *       is really a validation failure, is only visible in the prose. If the
 *       model is down or hedges, the status-code answer is still there.</li>
 * </ul>
 */
public class HybridFailureClassifier implements FailureClassifier {

	private final FailureClassifier heuristic;
	private final FailureClassifier model;

	public HybridFailureClassifier(FailureClassifier heuristic, FailureClassifier model) {
		this.heuristic = heuristic;
		this.model = model;
	}

	@Override
	public FailureClassification classify(FailureContext context) {
		if (!context.hasBody()) {
			return heuristic.classify(context);
		}

		FailureClassification fromModel = model.classify(context);
		return fromModel.category() == FailureCategory.UNKNOWN
				? heuristic.classify(context)
				: fromModel;
	}
}
