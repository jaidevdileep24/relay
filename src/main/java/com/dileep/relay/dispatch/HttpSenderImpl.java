package com.dileep.relay.dispatch;


import com.dileep.relay.config.RelayProperties;
import com.dileep.relay.service.SsrfGuard;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;


@Component
public class HttpSenderImpl implements  HttpSender{
    private static final int MAX_BODY_CHARS = 2000;

    private final HttpClient httpClient;
    private final SignatureSigner signer;
    private final SsrfGuard ssrfGuard;
    private final Duration timeout;

    public HttpSenderImpl(SignatureSigner signer, SsrfGuard ssrfGuard, RelayProperties properties) {
        this.signer = signer;
        this.ssrfGuard = ssrfGuard;
        this.timeout = Duration.ofMillis(properties.getDispatch().getHttpTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public SendResult send(DispatchTask task){
        long start = System.nanoTime();
        try {
            ssrfGuard.validate(task.url());

            Instant timeStamp = Instant.now();
            String signature = signer.sign(task.messageId().toString(), timeStamp, task.payload(), task.secret());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(task.url()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Relay/1.0")
                    .header("webhook-id", task.messageId().toString())
                    .header("webhook-timestamp", String.valueOf(timeStamp.getEpochSecond()))
                    .header("webhook-signature", signature)
                    .header("webhook-event-type", task.eventType())
                    .POST(HttpRequest.BodyPublishers.ofString(task.payload()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return new SendResult(response.statusCode(), truncate(response.body()), null, elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult(null, null, "interrupted", elapsedMs(start));
        } catch (Exception e) {
            return  new SendResult(null, null, describe(e), elapsedMs(start));
        }
    }

    private static int elapsedMs(long start) {
        return (int) ((System.nanoTime() - start) / 1_000_000);
    }

    private static String truncate(String body) {
        if (body == null) return null;
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS);
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

}
