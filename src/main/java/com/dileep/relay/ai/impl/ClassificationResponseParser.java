package com.dileep.relay.ai.impl;

import com.dileep.relay.ai.ClassificationPrompt;
import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.ai.FailureClassification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Turns a model's JSON into a {@link FailureClassification}, and refuses to
 * trust any of it.
 *
 * <p>Structured outputs make the shape valid, not the content sensible. Two
 * things are still enforced here:
 *
 * <ul>
 *   <li><b>Unrecognised category becomes UNKNOWN.</b> A model that invents a
 *       value must not crash dispatch.</li>
 *   <li><b>{@code retryAfter} is clamped and only honoured for RATE_LIMITED.</b>
 *       The value originates in a third party's response body. Left unbounded, a
 *       hostile or broken receiver could answer "retry after 400 days" and park
 *       a delivery forever.</li>
 * </ul>
 */
final class ClassificationResponseParser {

	private static final Logger log = LoggerFactory.getLogger(ClassificationResponseParser.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ClassificationResponseParser() {
	}

	static FailureClassification parse(String json, Duration maxRetryAfter) {
		try {
			JsonNode root = MAPPER.readTree(json);
			String reasoning = root.path("reasoning").asText("no reasoning given");

			FailureCategory category;
			try {
				category = FailureCategory.valueOf(root.path("category").asText());
			} catch (IllegalArgumentException e) {
				log.warn("classifier returned unknown category '{}'", root.path("category").asText());
				return FailureClassification.unknown("unrecognised category from classifier");
			}

			JsonNode retryAfter = root.path("retry_after_seconds");
			if (category == FailureCategory.RATE_LIMITED && retryAfter.isNumber() && retryAfter.asLong() > 0) {
				Duration requested = Duration.ofSeconds(retryAfter.asLong());
				Duration honoured = requested.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : requested;
				return FailureClassification.rateLimited(honoured, reasoning);
			}

			return FailureClassification.of(category, reasoning);
		} catch (Exception e) {
			// Never throw: an unparseable answer is a failure to answer, and the
			// caller falls back to the heuristic.
			log.warn("could not parse classifier response: {}", e.toString());
			return FailureClassification.unknown("unparseable classifier response");
		}
	}

	static String schema() {
		return ClassificationPrompt.JSON_SCHEMA;
	}
}
