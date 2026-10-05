package com.dileep.relay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(
		@NotBlank @Size(max = 200) @Pattern(regexp = Text.NO_NUL, message = Text.NO_NUL_MESSAGE) String name
) {}
