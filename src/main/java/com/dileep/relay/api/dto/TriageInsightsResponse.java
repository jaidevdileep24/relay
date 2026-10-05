package com.dileep.relay.api.dto;

import java.util.Map;

/**
 * What failure triage has done for one application - the numbers that answer
 * "is the AI worth it".
 *
 * @param provider            which classifier sits behind the heuristic: none | ollama | claude
 * @param failuresByCategory  failed attempts per category, most frequent first
 * @param deliveriesStoppedEarly deliveries given up on because a failure was
 *                            judged permanent while retry budget remained
 * @param retriesSaved        attempts those deliveries did not make - requests
 *                            that would have failed the same way
 * @param cachedVerdicts      distinct failure shapes classified (global)
 * @param cacheHits           classifications answered from cache instead of a
 *                            model call (global)
 */
public record TriageInsightsResponse(
		String provider,
		Map<String, Long> failuresByCategory,
		long deliveriesStoppedEarly,
		long retriesSaved,
		long cachedVerdicts,
		long cacheHits
) {}
