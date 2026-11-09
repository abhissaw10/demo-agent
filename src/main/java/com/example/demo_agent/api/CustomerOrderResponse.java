package com.example.demo_agent.api;

import java.time.LocalDateTime;

public record CustomerOrderResponse(
		String customerId,
		String orderId,
		LocalDateTime orderStartDate,
		OrderStatusResponse overallStatus) {
}
