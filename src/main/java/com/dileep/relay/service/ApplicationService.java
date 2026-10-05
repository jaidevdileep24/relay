package com.dileep.relay.service;

import com.dileep.relay.api.dto.ApplicationResponse;
import com.dileep.relay.api.dto.CreateApplicationRequest;
import com.dileep.relay.api.dto.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ApplicationService {

	ApplicationResponse create(CreateApplicationRequest request);

	ApplicationResponse get(UUID applicationId);

	PageResponse<ApplicationResponse> list(Pageable pageable);
}
