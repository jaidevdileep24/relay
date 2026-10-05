package com.dileep.relay.dispatch;

import java.time.Instant;

/**
 * Produces the HMAC signature a receiver uses to verify a webhook really
 * came from us, and was not replayed.
 *
 * <p>The signed string must include the timestamp, not just the payload.
 * Signing the payload alone lets an attacker who captures one valid request
 * replay it forever - every copy carries a perfectly valid signature.
 */
public interface SignatureSigner {

	/**
	 * @param messageId unique id of the message (also sent as a header, and
	 *                  what the receiver dedupes on)
	 * @param timestamp send time; receivers reject anything outside their
	 *                  tolerance window
	 * @param payload   the exact JSON body being sent
	 * @param secret    the endpoint's shared secret
	 * @return signature value for the {@code webhook-signature} header
	 */
	String sign(String messageId, Instant timestamp, String payload, String secret);
}
