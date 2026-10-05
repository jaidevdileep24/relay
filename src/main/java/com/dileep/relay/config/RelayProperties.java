package com.dileep.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the {@code relay.*} keys in application.yml.
 * Beats @Value everywhere: one place to see all tuning, and it fails fast
 * at startup if a key is malformed.
 */
@ConfigurationProperties(prefix = "relay")
public class RelayProperties {

	private Dispatch dispatch = new Dispatch();
	private Retry retry = new Retry();
	private Signature signature = new Signature();
	private Breaker breaker = new Breaker();
	private RateLimit rateLimit = new RateLimit();
	private Ai ai = new Ai();

	public static class Dispatch {
		private boolean enabled = true;
		private int batchSize = 100;
		private long pollIntervalMs = 1000;
		private int workerThreads = 8;
		private int httpTimeoutMs = 10_000;

		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean v) { this.enabled = v; }
		public int getBatchSize() { return batchSize; }
		public void setBatchSize(int v) { this.batchSize = v; }
		public long getPollIntervalMs() { return pollIntervalMs; }
		public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }
		public int getWorkerThreads() { return workerThreads; }
		public void setWorkerThreads(int v) { this.workerThreads = v; }
		public int getHttpTimeoutMs() { return httpTimeoutMs; }
		public void setHttpTimeoutMs(int v) { this.httpTimeoutMs = v; }
	}

	public static class Retry {
		private int maxAttempts = 8;
		private long baseDelayMs = 1000;
		private long maxDelayMs = 3_600_000;

		public int getMaxAttempts() { return maxAttempts; }
		public void setMaxAttempts(int v) { this.maxAttempts = v; }
		public long getBaseDelayMs() { return baseDelayMs; }
		public void setBaseDelayMs(long v) { this.baseDelayMs = v; }
		public long getMaxDelayMs() { return maxDelayMs; }
		public void setMaxDelayMs(long v) { this.maxDelayMs = v; }
	}

	public static class Signature {
		private long toleranceSeconds = 300;
		public long getToleranceSeconds() { return toleranceSeconds; }
		public void setToleranceSeconds(long v) { this.toleranceSeconds = v; }
	}

	public static class Breaker {
		private int failureThreshold = 5;

		public int getFailureThreshold() { return failureThreshold; }
		public void setFailureThreshold(int v) { this.failureThreshold = v; }
	}

	/** AI failure triage. Bound to {@code relay.ai.*}. */
	public static class Ai {

		/** Which classifier sits behind the heuristic. */
		public enum Provider { NONE, OLLAMA, CLAUDE }

		/** NONE keeps the project fully usable with no model running anywhere. */
		private Provider provider = Provider.NONE;

		private String ollamaBaseUrl = "http://localhost:11434";
		private String ollamaModel = "llama3.2";

		private String claudeModel = "claude-opus-5";
		/** Read from ANTHROPIC_API_KEY when blank. */
		private String anthropicApiKey = "";

		/** Six enum values and a sentence of reasoning need very little room. */
		private long maxTokens = 512;
		private long timeoutMs = 10_000;

		/**
		 * Ceiling on a receiver-supplied Retry-After. The value comes out of a
		 * third party's response body, so left unbounded a hostile or broken
		 * receiver could answer "retry in 400 days" and park a delivery forever.
		 */
		private long maxRetryAfterSecs = 3600;

		public java.time.Duration maxRetryAfter() { return java.time.Duration.ofSeconds(maxRetryAfterSecs); }

		public Provider getProvider() { return provider; }
		public void setProvider(Provider v) { this.provider = v; }
		public String getOllamaBaseUrl() { return ollamaBaseUrl; }
		public void setOllamaBaseUrl(String v) { this.ollamaBaseUrl = v; }
		public String getOllamaModel() { return ollamaModel; }
		public void setOllamaModel(String v) { this.ollamaModel = v; }
		public String getClaudeModel() { return claudeModel; }
		public void setClaudeModel(String v) { this.claudeModel = v; }
		public String getAnthropicApiKey() { return anthropicApiKey; }
		public void setAnthropicApiKey(String v) { this.anthropicApiKey = v; }
		public long getMaxTokens() { return maxTokens; }
		public void setMaxTokens(long v) { this.maxTokens = v; }
		public long getTimeoutMs() { return timeoutMs; }
		public void setTimeoutMs(long v) { this.timeoutMs = v; }
		public long getMaxRetryAfterSecs() { return maxRetryAfterSecs; }
		public void setMaxRetryAfterSecs(long v) { this.maxRetryAfterSecs = v; }
	}

	/** Per-endpoint send ceiling. Bound to {@code relay.rate-limit.*}. */
	public static class RateLimit {
		private boolean enabled = true;
		private double permitsPerSecond = 50;
		/** Headroom for a batch arriving at once; below the batch size this defers constantly. */
		private int burst = 50;
		/** How long a rate-limited delivery waits before being reconsidered. */
		private long deferMs = 1000;

		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean v) { this.enabled = v; }
		public double getPermitsPerSecond() { return permitsPerSecond; }
		public void setPermitsPerSecond(double v) { this.permitsPerSecond = v; }
		public int getBurst() { return burst; }
		public void setBurst(int v) { this.burst = v; }
		public long getDeferMs() { return deferMs; }
		public void setDeferMs(long v) { this.deferMs = v; }
	}

	public Dispatch getDispatch() { return dispatch; }
	public void setDispatch(Dispatch d) { this.dispatch = d; }
	public Retry getRetry() { return retry; }
	public void setRetry(Retry r) { this.retry = r; }
	public Signature getSignature() { return signature; }
	public void setSignature(Signature s) { this.signature = s; }
	public Breaker getBreaker() { return breaker; }
	public void setBreaker(Breaker b) { this.breaker = b; }
	public RateLimit getRateLimit() { return rateLimit; }
	public void setRateLimit(RateLimit r) { this.rateLimit = r; }
	public Ai getAi() { return ai; }
	public void setAi(Ai a) { this.ai = a; }
}
