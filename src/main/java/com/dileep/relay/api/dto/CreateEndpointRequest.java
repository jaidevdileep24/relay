package com.dileep.relay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * @param url        must be HTTPS. Beyond this regex, the service layer must
 *                   also run SSRF validation - reject private/link-local IPs
 *                   and re-check after DNS resolution.
 * @param eventTypes empty or null means "subscribe to all event types". Each
 *                   element gets the same rule as a message's eventType: a
 *                   blank one could never match, and a null one was silently
 *                   dropped on save, turning a filter into "everything".
 */
public record CreateEndpointRequest(
		@NotBlank
		@Size(max = 2048)
		@Pattern(regexp = "(?i)^https://[^\\x00]*", message = "endpoint url must use https")
		String url,

		@Size(max = 500)
		@Pattern(regexp = Text.NO_NUL, message = Text.NO_NUL_MESSAGE)
		String description,

		Set<@NotBlank @Size(max = 200) @Pattern(regexp = Text.NO_NUL, message = Text.NO_NUL_MESSAGE) String> eventTypes
) {}
