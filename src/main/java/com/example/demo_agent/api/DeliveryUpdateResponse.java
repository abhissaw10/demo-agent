package com.example.demo_agent.api;

import java.time.LocalDateTime;

public record DeliveryUpdateResponse(
		String statusCode,
		String status,
		String description,
		String details,
		LocalDateTime updatedAt,
		boolean current,
		boolean terminal) {
}
