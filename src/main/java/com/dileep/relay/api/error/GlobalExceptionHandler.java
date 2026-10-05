package com.dileep.relay.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates exceptions into consistent HTTP responses, so controllers can
 * stay free of try/catch and just throw.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiError> onNotFound(NotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiError.of(404, "not_found", ex.getMessage()));
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiError> onConflict(ConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiError.of(409, "conflict", ex.getMessage()));
	}

	@ExceptionHandler(UnprocessableException.class)
	public ResponseEntity<ApiError> onUnprocessable(UnprocessableException ex) {
		return ResponseEntity.unprocessableEntity()
				.body(ApiError.of(422, "unprocessable", ex.getMessage()));
	}

	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<ApiError> onValidation(ValidationException ex) {
		return ResponseEntity.badRequest()
				.body(ApiError.of(400, "invalid_request", ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> onBeanValidation(MethodArgumentNotValidException ex) {
		// One field can break several rules at once; joining keeps every
		// message instead of letting the last one silently win.
		Map<String, String> fields = new HashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(fe -> fields.merge(fe.getField(), String.valueOf(fe.getDefaultMessage()),
						(a, b) -> a + "; " + b));
		return ResponseEntity.badRequest().body(
				new ApiError(400, "validation_failed", "request body failed validation",
						fields, Instant.now()));
	}

	/**
	 * A query parameter or path variable that would not convert - {@code ?state=BANANA},
	 * a malformed UUID. That is the caller's mistake, so it is a 400.
	 *
	 * <p>Without this it falls through to {@link #onUnexpected(Exception)} and
	 * reports 500, which blames the server for bad input and lights up the
	 * error-rate alerts for routine typos.
	 *
	 * <p>The valid values are listed only for enums, and the required type is
	 * never printed - {@code getRequiredType()} renders as
	 * {@code class com.dileep.relay.domain.DeliveryState}, which hands out the
	 * package layout for nothing.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> onTypeMismatch(MethodArgumentTypeMismatchException ex) {
		Class<?> required = ex.getRequiredType();
		String allowed = required != null && required.isEnum()
				? " (expected one of " + Arrays.stream(required.getEnumConstants())
						.map(Object::toString).collect(Collectors.joining(", ")) + ")"
				: "";

		return ResponseEntity.badRequest().body(ApiError.of(400, "invalid_request",
				"invalid value for '%s': %s%s".formatted(ex.getName(), ex.getValue(), allowed)));
	}

	/**
	 * Two writers raced on the same row and this one lost - a double-clicked
	 * replay, say. {@code @Version} already refused the stale write, so the data
	 * is safe; the caller just needs to reload and try again. That is a 409, not
	 * a server fault.
	 */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ApiError> onOptimisticLock(ObjectOptimisticLockingFailureException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "conflict",
				"the resource was modified by another request; reload and retry"));
	}

	/**
	 * No database connection within the pool's timeout - we are overloaded,
	 * not broken. 503 with Retry-After tells a well-behaved client to back
	 * off and try again; a 500 tells it the request itself is bad. Found by
	 * the load test: at 3x the sustainable rate, ~0.5% of requests hit this.
	 */
	@ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
	public ResponseEntity<ApiError> onOverloaded(Exception ex) {
		log.warn("shedding load: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, "1")
				.body(ApiError.of(503, "overloaded", "the service is at capacity; retry after 1 second"));
	}

	/** Body that is not JSON at all, or JSON of the wrong shape. The parser's own message leaks class names, so it is not echoed. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> onUnreadableBody(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest()
				.body(ApiError.of(400, "invalid_request", "request body is missing or is not valid JSON"));
	}

	/**
	 * Last resort. Spring's own MVC exceptions - unknown path (404), wrong
	 * method (405), unsupported content type (415) - implement
	 * {@link ErrorResponse} and already carry the right status; without this
	 * check every one of them was reported as a 500 and logged as a server
	 * error. Only what is left is a genuine bug.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> onUnexpected(Exception ex) {
		if (ex instanceof ErrorResponse framework) {
			int status = framework.getStatusCode().value();
			HttpStatus resolved = HttpStatus.resolve(status);
			String error = resolved == null ? "error" : resolved.name().toLowerCase();
			if (status >= 500) {
				log.error("framework error", ex);
			}
			return ResponseEntity.status(status).headers(framework.getHeaders()).body(ApiError.of(status, error,
					resolved == null ? "request failed" : resolved.getReasonPhrase().toLowerCase()));
		}

		log.error("unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of(500, "internal_error", "an unexpected error occurred"));
	}
}
