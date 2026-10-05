package com.dileep.relay.ai.impl;

import com.dileep.relay.ai.ClassificationPrompt;
import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
import com.dileep.relay.ai.FailureContext;
import com.dileep.relay.config.RelayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Local classification through Ollama - the development provider.
 *
 * <p>Free and offline, so the feature can be exercised on every developer
 * machine and in tests without an API key or a bill. Quality is lower than
 * Claude's, which is the trade being made: dev proves the wiring, prod provides
 * the judgement.
 *
 * <p>Talks to Ollama over plain HTTP rather than through a client library. The
 * request is one POST with two fields; a dependency would be more code than it
 * removes.
 */
public class OllamaFailureClassifier implements FailureClassifier {

	private static final Logger log = LoggerFactory.getLogger(OllamaFailureClassifier.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpClient httpClient;
	private final RelayProperties.Ai config;

	public OllamaFailureClassifier(RelayProperties.Ai config) {
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(config.getTimeoutMs()))
				.build();
	}

	@Override
	public FailureClassification classify(FailureContext context) {
		try {
			ObjectNode body = MAPPER.createObjectNode();
			body.put("model", config.getOllamaModel());
			body.put("stream", false);
			body.put("system", ClassificationPrompt.SYSTEM);
			body.put("prompt", ClassificationPrompt.userMessage(context));
			// Ollama constrains generation to a JSON schema when `format` is an
			// object, which is the local equivalent of structured outputs.
			body.set("format", MAPPER.readTree(ClassificationResponseParser.schema()));

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(config.getOllamaBaseUrl() + "/api/generate"))
					.timeout(Duration.ofMillis(config.getTimeoutMs()))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("ollama returned HTTP {}", response.statusCode());
				return FailureClassification.unknown("ollama HTTP " + response.statusCode());
			}

			String generated = MAPPER.readTree(response.body()).path("response").asText();
			return ClassificationResponseParser.parse(generated, config.maxRetryAfter());

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return FailureClassification.unknown("interrupted");
		} catch (Exception e) {
			// Ollama not running is the normal case on a machine that never
			// installed it. Degrade, never fail.
			log.debug("ollama classification failed: {}", e.toString());
			return FailureClassification.unknown("ollama unavailable: " + e.getClass().getSimpleName());
		}
	}
}
