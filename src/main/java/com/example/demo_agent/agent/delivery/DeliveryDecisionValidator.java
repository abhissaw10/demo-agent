package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.DecisionValidationException;
import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.delivery.DeliveryAgentToolExecutor.DeliveryStatusFacts;
import com.example.demo_agent.agent.delivery.DeliveryAgentToolExecutor.OrderAccessFacts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class DeliveryDecisionValidator {

	private static final String EVIDENCE_SOURCE = "ORDER_FULFILLMENT_API";
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	DeliveryInvestigationResult parseAndValidate(String json, DeliveryInvestigationRequest request,
			List<ExecutedToolCall> evidence) {
		if (json == null || json.isBlank()) {
			throw new DecisionValidationException("The Delivery Agent must submit a structured result.");
		}
		final Draft draft;
		try {
			draft = objectMapper.readValue(json, Draft.class);
		} catch (JsonProcessingException exception) {
			throw new DecisionValidationException("The Delivery Agent result is not valid JSON.");
		}

		OrderAccessFacts access = latest(evidence, DeliveryAgentToolExecutor.RESOLVE_ORDER_ACCESS,
				OrderAccessFacts.class);
		if (access == null) {
			throw new DecisionValidationException("Order access must be resolved before finalizing.");
		}
		if (access.outcome() != DeliveryInvestigationOutcome.COMPLETED) {
			return new DeliveryInvestigationResult(access.outcome(), request.orderId(), null,
					access.summary(), List.of());
		}

		DeliveryStatusFacts status = latest(evidence, DeliveryAgentToolExecutor.GET_DELIVERY_STATUS,
				DeliveryStatusFacts.class);
		if (status == null) {
			throw new DecisionValidationException("Delivery status must be observed before finalizing.");
		}
		if (status.outcome() != DeliveryInvestigationOutcome.COMPLETED) {
			return new DeliveryInvestigationResult(status.outcome(), request.orderId(), null,
					status.failureSummary(), List.of());
		}

		if (draft.outcome() != DeliveryInvestigationOutcome.COMPLETED) {
			throw new DecisionValidationException("The verified delivery facts require outcome COMPLETED.");
		}
		if (!request.orderId().equals(draft.orderId())) {
			throw new DecisionValidationException("The result orderId must match the investigation request.");
		}
		if (!status.statusCode().equals(draft.statusCode())) {
			throw new DecisionValidationException("The result statusCode must match the delivery tool evidence.");
		}
		if (draft.summary() == null || draft.summary().isBlank()) {
			throw new DecisionValidationException("A concise factual summary is required.");
		}

		List<DeliveryEvidence> verifiedEvidence = status.observedAt() == null ? List.of()
				: List.of(new DeliveryEvidence("Latest status is " + status.statusCode(),
						EVIDENCE_SOURCE, status.observedAt()));
		return new DeliveryInvestigationResult(DeliveryInvestigationOutcome.COMPLETED,
				request.orderId(), status.statusCode(), draft.summary().trim(), verifiedEvidence);
	}

	private <T> T latest(List<ExecutedToolCall> evidence, String name, Class<T> type) {
		return evidence.stream()
				.filter(ExecutedToolCall::success)
				.filter(call -> name.equals(call.name()))
				.map(ExecutedToolCall::output)
				.filter(type::isInstance)
				.map(type::cast)
				.reduce((first, second) -> second)
				.orElse(null);
	}

	private record Draft(DeliveryInvestigationOutcome outcome, String orderId,
			String statusCode, String summary) {
	}
}
