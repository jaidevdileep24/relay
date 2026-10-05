package com.dileep.relay.config;

import com.dileep.relay.api.error.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Documents the error contract once instead of on every controller method:
 * each operation gains 400/404/409/422/500/503 responses using the {@link ApiError}
 * shape that {@code GlobalExceptionHandler} actually returns.
 */
@Configuration
public class OpenApiConfig {

	private static final Map<String, String> ERRORS = Map.of(
			"400", "Invalid request - validation failed or malformed input",
			"404", "Resource not found, or owned by another application",
			"409", "Conflicts with current state (e.g. replaying a PENDING delivery)",
			"422", "Idempotency-Key reused with a different request",
			"500", "Unexpected server error",
			"503", "At capacity - retry after the Retry-After header");

	@Bean
	public OpenApiCustomizer errorResponses() {
		return openApi -> {
			Map<String, Schema> schemas = ModelConverters.getInstance().read(ApiError.class);
			schemas.forEach(openApi.getComponents()::addSchemas);
			Content content = new Content().addMediaType("application/json",
					new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")));

			openApi.getPaths().values().forEach(path -> path.readOperations().forEach(op -> {
				ApiResponses responses = op.getResponses();
				ERRORS.forEach((code, description) -> responses.putIfAbsent(code,
						new ApiResponse().description(description).content(content)));
			}));
		};
	}
}
