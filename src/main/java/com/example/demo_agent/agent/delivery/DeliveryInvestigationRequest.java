package com.example.demo_agent.agent.delivery;

public record DeliveryInvestigationRequest(
		String customerId,
		String orderId,
		DeliveryInvestigationType investigationType) {
}
