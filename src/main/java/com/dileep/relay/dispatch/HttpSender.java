package com.dileep.relay.dispatch;


/**
 * Performs one HTTP POST to an endpoint.
 *
 * <p>Never throws for a failed delivery - transport errors come back inside
 * {@link SendResult} so the caller records an attempt either way.
 *
 * <p>Implementations must enforce a hard timeout. A receiver that takes 60
 * seconds is a failed receiver: without a timeout it occupies a worker thread
 * and starves every other tenant.
 */
public interface HttpSender {

	SendResult send(DispatchTask task);
}
