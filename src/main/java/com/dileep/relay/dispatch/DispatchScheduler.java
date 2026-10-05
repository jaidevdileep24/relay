package com.dileep.relay.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer that drives {@link DispatchWorker}. Nothing else.
 *
 * <p>It exists so that "is the dispatcher bean available" and "is the dispatcher
 * polling on a timer" are two separate questions. An integration test wants the
 * first and not the second: it needs the worker injected so it can call
 * {@code pollAndDispatch()} at a moment of its choosing, while the 1-second poll
 * must stay off or it would claim the rows the test just inserted and mutate
 * them mid-assertion.
 *
 * <p>Gating the worker itself on the flag made those two inseparable - switching
 * off the poll also deleted the bean under test.
 */
@Component
@ConditionalOnProperty(prefix = "relay.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispatchScheduler {

	private final DispatchWorker worker;

	public DispatchScheduler(DispatchWorker worker) {
		this.worker = worker;
	}

	/**
	 * The interval only applies once the queue is empty: {@link DispatchWorker#drain()}
	 * keeps going without pausing for as long as rows are due.
	 */
	@Scheduled(fixedDelayString = "${relay.dispatch.poll-interval-ms:1000}")
	public void poll() {
		worker.drain();
	}
}
