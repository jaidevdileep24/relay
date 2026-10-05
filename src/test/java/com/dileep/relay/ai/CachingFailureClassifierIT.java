package com.dileep.relay.ai;

import com.dileep.relay.AbstractIntegrationTest;
import com.dileep.relay.ai.impl.CachingFailureClassifier;
import com.dileep.relay.repository.FailureClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The cache is a spend guard, so every test here is really about how often the model gets called. */
class CachingFailureClassifierIT extends AbstractIntegrationTest {

	@Autowired FailureClassificationRepository repository;

	private final AtomicInteger delegateCalls = new AtomicInteger();

	@BeforeEach
	void clean() {
		repository.deleteAll();
		delegateCalls.set(0);
	}

	private CachingFailureClassifier caching(FailureClassification answer) {
		FailureClassifier delegate = context -> {
			delegateCalls.incrementAndGet();
			return answer;
		};
		return new CachingFailureClassifier(delegate, repository, "test:stub");
	}

	private static FailureContext body(String text) {
		return new FailureContext(500, text, null, "invoice.paid");
	}

	@Test
	@DisplayName("the same failure is classified once, however often it recurs")
	void secondCallIsFree() {
		CachingFailureClassifier classifier =
				caching(FailureClassification.of(FailureCategory.AUTH, "invalid signature"));

		for (int i = 0; i < 5; i++) {
			assertThat(classifier.classify(body("invalid signature")).category())
					.isEqualTo(FailureCategory.AUTH);
		}

		// An endpoint that is down returns the same body thousands of times an
		// hour. This ratio is the entire cost argument for the phase.
		assertThat(delegateCalls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("volatile ids in the body do not defeat the cache")
	void normalisationKeepsTheHitRateUp() {
		CachingFailureClassifier classifier =
				caching(FailureClassification.of(FailureCategory.SERVER_ERROR, "upstream"));

		classifier.classify(body("request 8fa21bc09d failed at 10:32:11"));
		classifier.classify(body("request 9bc03ff17a failed at 11:04:58"));

		assertThat(delegateCalls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("different failures are classified separately")
	void distinctFailuresAreNotConflated() {
		CachingFailureClassifier classifier =
				caching(FailureClassification.of(FailureCategory.SERVER_ERROR, "x"));

		classifier.classify(body("invalid signature"));
		classifier.classify(body("unknown event type"));

		assertThat(delegateCalls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("hit count is tracked, so the cache can prove it is earning its keep")
	void hitsAreCounted() {
		CachingFailureClassifier classifier =
				caching(FailureClassification.of(FailureCategory.AUTH, "nope"));

		classifier.classify(body("invalid signature"));      // miss, stores
		classifier.classify(body("invalid signature"));      // hit
		classifier.classify(body("invalid signature"));      // hit

		assertThat(repository.findAll()).singleElement()
				.satisfies(row -> assertThat(row.getHitCount()).isEqualTo(2));
	}

	@Test
	@DisplayName("retryAfter survives the round trip through the cache")
	void retryAfterIsPersisted() {
		CachingFailureClassifier classifier =
				caching(FailureClassification.rateLimited(Duration.ofSeconds(90), "slow down"));

		classifier.classify(body("too many requests"));
		FailureClassification cached = classifier.classify(body("too many requests"));

		assertThat(cached.category()).isEqualTo(FailureCategory.RATE_LIMITED);
		assertThat(cached.retryAfter()).isEqualTo(Duration.ofSeconds(90));
	}

	@Test
	@DisplayName("UNKNOWN is never cached - one flaky call must not poison a fingerprint forever")
	void unknownIsNotCached() {
		CachingFailureClassifier classifier = caching(FailureClassification.unknown("model down"));

		classifier.classify(body("something went wrong"));
		classifier.classify(body("something went wrong"));

		assertThat(delegateCalls.get()).isEqualTo(2);
		assertThat(repository.count()).isZero();
	}
}
