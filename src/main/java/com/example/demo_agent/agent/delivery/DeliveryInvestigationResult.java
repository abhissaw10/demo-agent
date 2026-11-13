package com.example.demo_agent.agent.delivery;

import java.util.List;

public record DeliveryInvestigationResult(
		DeliveryInvestigationOutcome outcome,
		String orderId,
		String statusCode,
		String summary,
		List<DeliveryEvidence> evidence) {

	public DeliveryInvestigationResult {
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
	}
}
