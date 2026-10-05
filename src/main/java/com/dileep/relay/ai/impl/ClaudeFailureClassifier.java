package com.dileep.relay.ai.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.dileep.relay.ai.ClassificationPrompt;
import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
import com.dileep.relay.ai.FailureContext;
import com.dileep.relay.config.RelayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production classification through Claude.
 *
 * <p>Uses structured outputs ({@code output_config.format}) rather than "please
 * reply with JSON" in the prompt. The schema is enforced during generation, so
 * the response cannot come back as prose, as JSON wrapped in a markdown fence,
 * or with an invented category - the failure mode that makes prompt-only JSON
 * unusable in a retry path.
 *
 * <p>Effort is {@code LOW} and {@code maxTokens} is small on purpose. This is a
 * six-way classification over a short body, not a reasoning task, and it runs on
 * every distinct failure. Paired with the fingerprint cache in front of it, the
 * bill stays near zero.
 */
public class ClaudeFailureClassifier implements FailureClassifier {

	private static final Logger log = LoggerFactory.getLogger(ClaudeFailureClassifier.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AnthropicClient client;
	private final RelayProperties.Ai config;

	public ClaudeFailureClassifier(AnthropicClient client, RelayProperties.Ai config) {
		this.client = client;
		this.config = config;
	}

	@Override
	public FailureClassification classify(FailureContext context) {
		try {
			MessageCreateParams params = MessageCreateParams.builder()
					.model(config.getClaudeModel())
					.maxTokens(config.getMaxTokens())
					.system(ClassificationPrompt.SYSTEM)
					.addUserMessage(ClassificationPrompt.userMessage(context))
					.outputConfig(OutputConfig.builder()
							.effort(OutputConfig.Effort.LOW)
							.format(JsonOutputFormat.builder()
									.schema(schema())
									.build())
							.build())
					.build();

			Message response = client.messages().create(params);

			String json = response.content().stream()
					.flatMap(block -> block.text().stream())
					.map(text -> text.text())
					.findFirst()
					.orElse(null);

			if (json == null) {
				return FailureClassification.unknown("claude returned no text block");
			}
			return ClassificationResponseParser.parse(json, config.maxRetryAfter());

		} catch (Exception e) {
			// Rate limits, outages, expired keys. A classifier that cannot answer
			// must not stop deliveries being dispatched.
			log.warn("claude classification failed: {}", e.toString());
			return FailureClassification.unknown("claude unavailable: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * The SDK models the schema as free-form JSON, so it is built by copying the
	 * shared schema in as generic properties - keeping one schema definition for
	 * both providers rather than two that can drift.
	 */
	private static JsonOutputFormat.Schema schema() {
		try {
			JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
			MAPPER.readTree(ClassificationResponseParser.schema()).properties()
					.forEach(entry -> builder.putAdditionalProperty(
							entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
			return builder.build();
		} catch (Exception e) {
			throw new IllegalStateException("classification schema is not valid JSON", e);
		}
	}
}
