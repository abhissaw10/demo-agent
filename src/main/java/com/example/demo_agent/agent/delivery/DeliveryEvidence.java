package com.example.demo_agent.agent.delivery;

import java.time.Instant;

public record DeliveryEvidence(
		String fact,
		String source,
		Instant observedAt) {
}
