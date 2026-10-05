package com.dileep.relay.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.dileep.relay.ai.impl.CachingFailureClassifier;
import com.dileep.relay.ai.impl.ClaudeFailureClassifier;
import com.dileep.relay.ai.impl.HeuristicFailureClassifier;
import com.dileep.relay.ai.impl.HybridFailureClassifier;
import com.dileep.relay.ai.impl.OllamaFailureClassifier;
import com.dileep.relay.config.RelayProperties;
import com.dileep.relay.repository.FailureClassificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the classifier stack from {@code relay.ai.provider}.
 *
 * <p>The stack, outermost first:
 *
 * <pre>
 *   HybridFailureClassifier      no body? answer free. otherwise ask the model.
 *     +-- CachingFailureClassifier   same failure classified once, not once per delivery
 *     |     +-- Ollama | Claude
 *     +-- HeuristicFailureClassifier fallback whenever the model cannot answer
 * </pre>
 *
 * <p>Wired here with {@code new} rather than as component-scanned beans so the
 * composition is one readable expression. The alternative - conditional
 * annotations spread across five classes - hides the order, and the order is the
 * design.
 */
@Configuration
public class FailureClassifierConfig {

	private static final Logger log = LoggerFactory.getLogger(FailureClassifierConfig.class);

	@Bean
	public FailureClassifier failureClassifier(RelayProperties properties,
	                                           FailureClassificationRepository repository) {
		RelayProperties.Ai ai = properties.getAi();
		FailureClassifier heuristic = new HeuristicFailureClassifier();

		FailureClassifier model = switch (ai.getProvider()) {
			case NONE -> null;
			case OLLAMA -> new OllamaFailureClassifier(ai);
			case CLAUDE -> new ClaudeFailureClassifier(anthropicClient(ai), ai);
		};

		if (model == null) {
			log.info("AI failure triage disabled; using status-code heuristics only");
			return heuristic;
		}

		String name = ai.getProvider() == RelayProperties.Ai.Provider.CLAUDE
				? "claude:" + ai.getClaudeModel()
				: "ollama:" + ai.getOllamaModel();

		log.info("AI failure triage enabled via {}", name);
		return new HybridFailureClassifier(
				heuristic, new CachingFailureClassifier(model, repository, name));
	}

	/**
	 * Built here rather than as a bean so that no Anthropic client is constructed
	 * - and no API key is required - unless the Claude provider is selected.
	 */
	private static AnthropicClient anthropicClient(RelayProperties.Ai ai) {
		return ai.getAnthropicApiKey() == null || ai.getAnthropicApiKey().isBlank()
				? AnthropicOkHttpClient.fromEnv()
				: AnthropicOkHttpClient.builder().apiKey(ai.getAnthropicApiKey()).build();
	}
}
