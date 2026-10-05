package com.dileep.relay.ai.impl;

import com.dileep.relay.ai.FailureCategory;
import com.dileep.relay.ai.FailureClassification;
import com.dileep.relay.ai.FailureClassifier;
import com.dileep.relay.ai.FailureContext;
import com.dileep.relay.ai.FailureFingerprint;
import com.dileep.relay.domain.FailureClassificationCache;
import com.dileep.relay.repository.FailureClassificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;

/**
 * Wraps a paid classifier with a fingerprint cache, so a failure is classified
 * once rather than once per delivery.
 *
 * <p>The three database calls here are deliberately three separate short
 * transactions with the model call sitting <i>between</i> them, never inside
 * one. Holding a pooled connection across an LLM round trip is the same mistake
 * as holding one across an HTTP send, and exhausts the pool the same way.
 */
public class CachingFailureClassifier implements FailureClassifier {

	private static final Logger log = LoggerFactory.getLogger(CachingFailureClassifier.class);

	private final FailureClassifier delegate;
	private final FailureClassificationRepository repository;
	private final String classifierName;

	public CachingFailureClassifier(FailureClassifier delegate,
	                                FailureClassificationRepository repository,
	                                String classifierName) {
		this.delegate = delegate;
		this.repository = repository;
		this.classifierName = classifierName;
	}

	@Override
	public FailureClassification classify(FailureContext context) {
		String fingerprint = FailureFingerprint.of(context);

		FailureClassification cached = lookup(fingerprint);
		if (cached != null) {
			log.debug("classification cache hit for {}", fingerprint);
			return cached;
		}

		FailureClassification fresh = delegate.classify(context);

		// An UNKNOWN is not an answer, it is a failure to answer - caching it
		// would make one flaky model call permanently poison this fingerprint.
		if (fresh.category() != FailureCategory.UNKNOWN) {
			store(fingerprint, fresh);
		}
		return fresh;
	}

	/**
	 * No {@code @Transactional} anywhere in this class on purpose: it is built
	 * with {@code new} in {@link FailureClassifierConfig}, so Spring never
	 * proxies it and the annotation would be silently inert. Each repository
	 * call is its own short transaction instead, which is exactly what is wanted
	 * - the model call must not sit inside one.
	 */
	private FailureClassification lookup(String fingerprint) {
		return repository.findById(fingerprint)
				.map(this::toClassification)
				.orElse(null);
	}

	private void store(String fingerprint, FailureClassification classification) {
		try {
			repository.save(new FailureClassificationCache(
					fingerprint,
					classification.category(),
					classification.retryAfter() == null ? null : classification.retryAfter().toSeconds(),
					classification.reasoning(),
					classifierName));
		} catch (Exception e) {
			// Two threads classifying the same new fingerprint race on the primary
			// key. The loser has a perfectly good answer in hand; caching is an
			// optimisation and must never fail the classification.
			log.debug("could not cache classification for {}: {}", fingerprint, e.toString());
		}
	}

	private FailureClassification toClassification(FailureClassificationCache row) {
		repository.recordHit(row.getFingerprint());
		Duration retryAfter = row.getRetryAfterSecs() == null
				? null
				: Duration.ofSeconds(row.getRetryAfterSecs());
		return new FailureClassification(
				row.getCategory(), row.getCategory().isRetryable(), retryAfter, row.getReasoning());
	}
}
