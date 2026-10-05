package com.dileep.relay.api;

import com.dileep.relay.api.dto.ApplicationResponse;
import com.dileep.relay.api.dto.CreateApplicationRequest;
import com.dileep.relay.api.dto.PageResponse;
import com.dileep.relay.service.ApplicationService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

	private static final Set<String> SORT = Set.of("name", "createdAt");

	private final ApplicationService applicationService;

	public ApplicationController(ApplicationService applicationService) {
		this.applicationService = applicationService;
	}

	@PostMapping
	@ApiResponse(responseCode = "201", description = "Application created; Location points at it")
	public ResponseEntity<ApplicationResponse> create(@Valid @RequestBody CreateApplicationRequest request) {
		ApplicationResponse created = applicationService.create(request);
		return ResponseEntity.created(URI.create("/api/v1/applications/" + created.id())).body(created);
	}

	@GetMapping("/{applicationId}")
	public ApplicationResponse get(@PathVariable UUID applicationId) {
		return applicationService.get(applicationId);
	}

	/** Paged: an unbounded list grows with every tenant ever created. */
	@GetMapping
	public PageResponse<ApplicationResponse> list(
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return applicationService.list(Pages.check(pageable, SORT));
	}
}
