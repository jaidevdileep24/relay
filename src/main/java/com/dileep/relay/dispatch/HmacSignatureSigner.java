package com.dileep.relay.dispatch;


import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;

/**
 * HMAC-SHA256 over {@code messageId.timestamp.payload}.
 *
 * <p>The timestamp is signed too. Sign the payload alone and a captured request
 * can be replayed forever - every copy carries a valid signature. Receivers
 * reject anything outside {@code relay.signature.tolerance-seconds}.
 *
 * <p>A new {@link Mac} per call: it is stateful and not thread-safe, and 8
 * dispatcher threads share this bean.
 *
 * <p>The {@code v1,} prefix lets the algorithm be rotated later without
 * breaking receivers that only understand v1.
 */
@Component
public class HmacSignatureSigner implements SignatureSigner {
    private static final String  ALGORITHM = "HmacSHA256";

    @Override
    public String sign(String messageId, Instant timestamp, String payload, String secret) {
        String signedContent = messageId + "." + timestamp.getEpochSecond() + "." + payload;

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign webhook payload", e);
        }
    }
}
