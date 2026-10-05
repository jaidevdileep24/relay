package com.dileep.relay.ai;

import com.dileep.relay.ai.impl.OllamaFailureClassifier;
import com.dileep.relay.config.RelayProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the real local model. Skipped when Ollama is not running, so the
 * suite still passes on a machine that never installed it - the classifier is
 * optional and its tests have to be too.
 *
 * <p>Assertions are deliberately loose. This proves the wiring end to end -
 * request shape, schema-constrained generation, parsing, clamping - not that a
 * 1B model has good judgement. Pinning exact categories here would produce a
 * test that fails whenever the local model is swapped.
 */
class OllamaFailureClassifierIT {

	private static final String BASE_URL = "http://localhost:11434";

	private static boolean ollamaIsRunning;

	@BeforeAll
	static void probe() {
		try {
			HttpResponse<String> response = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(2)).build()
					.send(HttpRequest.newBuilder()
									.uri(URI.create(BASE_URL + "/api/tags"))
									.timeout(Duration.ofSeconds(2)).GET().build(),
							HttpResponse.BodyHandlers.ofString());
			ollamaIsRunning = response.statusCode() == 200;
		} catch (Exception e) {
			ollamaIsRunning = false;
		}
	}

	private static OllamaFailureClassifier classifier() {
		RelayProperties.Ai config = new RelayProperties().getAi();
		config.setOllamaBaseUrl(BASE_URL);
		config.setOllamaModel("llama3.2");
		config.setTimeoutMs(60_000);
		return new OllamaFailureClassifier(config);
	}

	@Test
	@DisplayName("a real model round trip returns a schema-valid category")
	void realRoundTrip() {
		assumeTrue(ollamaIsRunning, "ollama not running");

		FailureClassification result = classifier().classify(new FailureContext(
				401, "{\"error\":\"invalid webhook signature\"}", null, "invoice.paid"));

		// The contract: always a usable category, never an exception.
		assertThat(result.category()).isNotNull();
		assertThat(result.reasoning()).isNotBlank();
	}

	@Test
	@DisplayName("a body that contradicts the status code is what the model is here for")
	void readsTheBodyNotJustTheStatus() {
		assumeTrue(ollamaIsRunning, "ollama not running");

		// 200 OK carrying an auth error - invisible to any status-code switch.
		FailureClassification result = classifier().classify(new FailureContext(
				200, "{\"ok\":false,\"error\":\"signature verification failed\"}", null, "invoice.paid"));

		assertThat(result.category()).isNotNull();
	}

	@Test
	@DisplayName("an unreachable Ollama degrades to UNKNOWN instead of throwing")
	void unreachableDegrades() {
		RelayProperties.Ai config = new RelayProperties().getAi();
		config.setOllamaBaseUrl("http://localhost:1");       // nothing listens here
		config.setTimeoutMs(1000);

		FailureClassification result = new OllamaFailureClassifier(config)
				.classify(new FailureContext(500, "boom", null, "invoice.paid"));

		// The dispatcher must keep working when the model is down.
		assertThat(result.category()).isEqualTo(FailureCategory.UNKNOWN);
	}
}
