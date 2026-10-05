package com.dileep.relay.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(UUID id, String name, Instant createdAt) {}
