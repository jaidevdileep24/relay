package com.dileep.relay.service.impl;

import com.dileep.relay.api.dto.ApplicationResponse;
import com.dileep.relay.api.dto.CreateApplicationRequest;
import com.dileep.relay.api.dto.PageResponse;
import com.dileep.relay.api.error.NotFoundException;
import com.dileep.relay.domain.Application;
import com.dileep.relay.repository.ApplicationRepository;
import com.dileep.relay.service.ApplicationService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ApplicationServiceImpl implements ApplicationService {

	private final ApplicationRepository applicationRepository;

	public ApplicationServiceImpl(ApplicationRepository applicationRepository) {
		this.applicationRepository = applicationRepository;
	}

	@Override
	@Transactional
	public ApplicationResponse create(CreateApplicationRequest request) {
		Application application = new Application(request.name());
		applicationRepository.save(application);
		return toResponse(application);
	}

	@Override
	@Transactional(readOnly = true)
	public ApplicationResponse get(UUID applicationId) {
		return applicationRepository.findById(applicationId).
				map(ApplicationServiceImpl::toResponse)
				.orElseThrow(() -> new NotFoundException("Application" , applicationId));
	}

	@Override
	@Transactional(readOnly = true)
	public PageResponse<ApplicationResponse> list(Pageable pageable) {
		return PageResponse.of(applicationRepository.findAll(pageable), ApplicationServiceImpl::toResponse);
	}

	private static ApplicationResponse toResponse(Application application) {
		return new ApplicationResponse(
				application.getId(),
				application.getName(),
				application.getCreatedAt()
		);
	}
}
