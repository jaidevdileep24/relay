package com.dileep.relay.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable hash of a failure, so the same broken endpoint is classified once
 * rather than once per delivery.
 *
 * <p>This is what keeps the model spend near zero. A receiver that is down
 * returns the same body thousands of times in an hour; the cache turns that
 * into one call.
 *
 * <p>The normalisation is the whole trick. Raw bodies almost always carry a
 * request id or timestamp, and hashing those verbatim gives every occurrence a
 * unique key - a cache with a 0% hit rate that still costs a database round
 * trip. Digits are collapsed so {@code "request 8fa21 failed at 10:32:11"} and
 * {@code "request 9bc03 failed at 10:32:47"} fingerprint identically.
 */
public final class FailureFingerprint {

	/** Long bodies are dominated by their opening lines; the tail is usually a stack trace. */
	private static final int MAX_CHARS = 1024;

	private FailureFingerprint() {
	}

	public static String of(FailureContext context) {
		String material = context.httpStatus() + "\n"
				+ normalise(context.responseBody()) + "\n"
				+ normalise(context.errorMessage());

		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(material.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every JVM; if it is missing, nothing else works either.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static String normalise(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String trimmed = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
		return trimmed.toLowerCase()
				.replaceAll("[0-9a-f]{8,}", "#")   // uuids, hashes, request ids
				.replaceAll("\\d+", "#")           // timestamps, counts, ports
				.replaceAll("\\s+", " ")
				.trim();
	}
}
