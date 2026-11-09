package com.example.demo_agent.api;

import java.time.LocalDateTime;

public record OrderStatusResponse(
		String code,
		String displayName,
		String description,
		LocalDateTime updatedAt,
		boolean terminal) {
}
