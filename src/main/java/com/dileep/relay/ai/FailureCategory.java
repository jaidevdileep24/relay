package com.dileep.relay.ai;

/**
 * What to do next about a failed delivery - not what the status code said.
 *
 * <p>Each category exists because it changes dispatcher behaviour. Two
 * categories handled identically would be one category.
 */
public enum FailureCategory {

	/** Signature rejected, bad or revoked key. Retrying fails identically. */
	AUTH(false),

	/** Malformed payload, unknown event type. Our request is wrong; time will not fix it. */
	CLIENT_ERROR(false),

	/** The receiver is throttling us. Retry, but on their schedule rather than ours. */
	RATE_LIMITED(true),

	/** The receiver is broken but probably not forever. */
	SERVER_ERROR(true),

	/** Timeout, DNS, TLS - we never got an answer. */
	NETWORK(true),

	/**
	 * No usable classification: the classifier was unavailable, its output would
	 * not parse, or it hedged.
	 *
	 * <p>The safe default, and the reason this phase cannot take the dispatcher
	 * down with it - UNKNOWN falls straight back to the retry policy that has
	 * been running since P2.
	 */
	UNKNOWN(true);

	private final boolean retryable;

	FailureCategory(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
