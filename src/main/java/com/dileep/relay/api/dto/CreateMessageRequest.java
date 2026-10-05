package com.dileep.relay.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param eventType e.g. "invoice.paid" - endpoints subscribe by this string.
 * @param payload   a JSON object or array, stored verbatim as JSONB. An explicit
 *                  {@code null} arrives as Jackson's {@code NullNode}, which
 *                  {@code @NotNull} does not catch - the service checks the shape.
 */
public record CreateMessageRequest(
		@NotBlank @Size(max = 200) @Pattern(regexp = Text.NO_NUL, message = Text.NO_NUL_MESSAGE) String eventType,
		@NotNull JsonNode payload
) {}
