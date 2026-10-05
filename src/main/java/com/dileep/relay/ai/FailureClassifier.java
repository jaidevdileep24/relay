package com.dileep.relay.ai;

/**
 * Decides why a delivery failed, so the dispatcher can stop treating every
 * failure the same way.
 *
 * <p>Retrying a 401 eight times over an hour is guaranteed to fail eight times.
 * Ignoring a 429's {@code Retry-After} and using our own backoff is how you get
 * throttled harder. The signal is often in unstructured body text rather than
 * the status code - plenty of APIs return {@code 200 OK} with
 * {@code {"error":"invalid signature"}} - which is why this is an interface with
 * a language model behind it rather than a switch statement.
 *
 * <p><b>Implementations must never throw.</b> A classifier that is down, slow,
 * or returning nonsense has to degrade to
 * {@link FailureClassification#unknown(String)}, which restores the retry
 * behaviour the dispatcher had before this phase existed. The AI is an
 * optimisation, never a dependency.
 *
 * <p><b>Implementations must never be called inside a database transaction.</b>
 * Same rule as the HTTP sender: a model call holds a pooled connection for the
 * length of a network round trip and will exhaust HikariCP.
 */
public interface FailureClassifier {

	FailureClassification classify(FailureContext context);
}
