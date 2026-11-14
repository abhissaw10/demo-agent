package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.DecisionValidationException;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationOutcome;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDecisionValidatorTest {

	private final AgentDecisionValidator validator = new AgentDecisionValidator();

	@Test
	void rejectsSilentOrderSelectionWhenSeveralOwnedOrdersMatch() {
		LocalDateTime date = LocalDateTime.of(2026, 10, 25, 9, 30);
		OrderStatusResponse status = new OrderStatusResponse("ORDER_PLACED", "Order placed", "Accepted", date, false);
		List<CustomerOrderResponse> orders = List.of(
				new CustomerOrderResponse("CUST-1001", "ORD-1001", date, status),
				new CustomerOrderResponse("CUST-1001", "ORD-1002", date.plusDays(1), status));
		ExecutedToolCall evidence = new ExecutedToolCall("call-1", GetCustomerOrdersTool.NAME, orders, true, null);
		DeliveryInvestigationResult delivery = new DeliveryInvestigationResult(
				DeliveryInvestigationOutcome.COMPLETED, "ORD-1001", "IN_TRANSIT",
				"The order is in transit.", List.of());
		String decision = """
				{"message":"ORD-1001 is being prepared.","outcome":"ANSWERED",
				"resolvedIntents":["delivery"],"orderId":"ORD-1001","evidence":["Order exists."],
				"recommendations":[],"actionsRequiringApproval":[]}
				""";

		assertThatThrownBy(() -> validator.parseAndValidate(decision, "Where is my delivery?", List.of(),
				List.of(evidence), delivery))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("Multiple orders match");
	}

	@Test
	void acceptsConciseFollowUpThatAnswersOnlyTheNewIntent() {
		String decision = """
				{"message":"No. ORD-1002 is still within the 14-day delivery SLA, so it is not yet eligible for cancellation and a refund.",
				"outcome":"ANSWERED","resolvedIntents":["refund eligibility"],"orderId":"ORD-1002",
				"evidence":["The refund policy requires the 14-day delivery SLA to pass."],
				"recommendations":["Wait until the SLA has passed."],"actionsRequiringApproval":["Cancellation and refund"]}
				""";
		List<ConversationTurn> history = List.of(new ConversationTurn(
				"What is the status of ORD-1002?",
				"ORD-1002 is awaiting inventory refresh and has not shipped yet."));
		ExecutedToolCall refundPolicy = new ExecutedToolCall("call-1", GetRefundPolicyTool.NAME,
				"14-day delivery SLA", true, null);

		AgentDecision result = validator.parseAndValidate(decision, "Can I cancel it and get a refund?",
				history, List.of(refundPolicy));

		assertThat(result.message()).startsWith("No.").hasSizeLessThan(150);
	}

	@Test
	void rejectsARequestForAnOrderDateAlreadyPresentInVerifiedHistory() {
		LocalDateTime placedAt = LocalDateTime.of(2026, 10, 18, 8, 0);
		CustomerOrderResponse order = new CustomerOrderResponse("CUST-1003", "ORD-1005", placedAt,
				new OrderStatusResponse("LOST_IN_TRANSIT", "Lost in transit",
						"The carrier declared the shipment lost.", placedAt.plusDays(3), true));
		List<ConversationTurn> history = List.of(new ConversationTurn(
				"What is the status of ORD-1005?", "ORD-1005 is lost in transit.", List.of(
						new ExecutedToolCall("orders", GetCustomerOrdersTool.NAME,
								List.of(order), true, null))));
		String decision = """
				{"message":"Could you let me know when you placed the order?",
				"outcome":"CLARIFICATION_REQUIRED","resolvedIntents":["refund eligibility"],
				"orderId":"ORD-1005","evidence":[],"recommendations":[],
				"actionsRequiringApproval":[]}
				""";

		assertThatThrownBy(() -> validator.parseAndValidate(decision,
				"Cancel the order and issue me a refund", history, List.of()))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("placement date is already present");
	}

	@Test
	void rejectsAnOverlyLongFollowUp() {
		String message = "policy ".repeat(51).trim();
		String decision = """
				{"message":"%s","outcome":"ANSWERED","resolvedIntents":["refund eligibility"],
				"orderId":"ORD-1002","evidence":["Verified refund policy."],
				"recommendations":[],"actionsRequiringApproval":[]}
				""".formatted(message);
		List<ConversationTurn> history = List.of(new ConversationTurn("Status?", "ORD-1002 is awaiting inventory."));

		assertThatThrownBy(() -> validator.parseAndValidate(decision, "Can I get a refund?", history,
				List.of(new ExecutedToolCall("call-1", GetRefundPolicyTool.NAME, "policy", true, null))))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("no more than 50 words");
	}

	@Test
	void rejectsAFollowUpThatRepeatsThePriorAnswer() {
		String priorAnswer = "ORD-1002 is awaiting inventory refresh and has not shipped because inventory is being reconciled.";
		String decision = """
				{"message":"ORD-1002 is awaiting inventory refresh and has not shipped because inventory is being reconciled. It is still within the refund SLA.",
				"outcome":"ANSWERED","resolvedIntents":["refund eligibility"],"orderId":"ORD-1002",
				"evidence":["Verified refund policy."],"recommendations":[],"actionsRequiringApproval":[]}
				""";

		assertThatThrownBy(() -> validator.parseAndValidate(decision, "Can I get a refund?",
				List.of(new ConversationTurn("Where is my order?", priorAnswer)),
				List.of(new ExecutedToolCall("call-1", GetRefundPolicyTool.NAME, "policy", true, null))))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("repeats information already communicated");
	}

	@Test
	void acceptsACompletedDeliveryAnswerWhenCustomerHasNoOrders() {
		String decision = """
				{"message":"No orders were found for this customer.","outcome":"ANSWERED",
				"resolvedIntents":["delivery status"],"orderId":null,
				"evidence":["The customer order lookup found no orders."],
				"recommendations":[],"actionsRequiringApproval":[]}
				""";
		ExecutedToolCall noOrders = new ExecutedToolCall("call-1", GetCustomerOrdersTool.NAME,
				Map.of("error", "CUSTOMER_ORDERS_NOT_FOUND"), false, null);

		AgentDecision result = validator.parseAndValidate(decision, "Where is ORD-404?",
				List.of(), List.of(noOrders));

		assertThat(result.outcome()).isEqualTo("ANSWERED");
	}

	@Test
	void acceptsRefundAndExchangeAnswersWithoutPolicyToolsWhenAllOrdersAreDelivered() {
		LocalDateTime deliveredAt = LocalDateTime.of(2026, 11, 5, 15, 0);
		CustomerOrderResponse delivered = new CustomerOrderResponse("CUST-1004", "ORD-1006",
				deliveredAt.minusDays(3), new OrderStatusResponse(
						"DELIVERED", "Delivered", "Delivered", deliveredAt, true));
		ExecutedToolCall orders = new ExecutedToolCall("call-1", GetCustomerOrdersTool.NAME,
				List.of(delivered), true, null);

		for (String intent : List.of("refund", "exchange")) {
			String decision = """
					{"message":"There are no unfulfilled orders for this request.","outcome":"ANSWERED",
					"resolvedIntents":["%s eligibility"],"orderId":"ORD-1006",
					"evidence":["The only owned order is delivered."],
					"recommendations":[],"actionsRequiringApproval":[]}
					""".formatted(intent);

			AgentDecision result = validator.parseAndValidate(decision,
					"Can I get an " + intent + " for ORD-1006?", List.of(), List.of(orders));

			assertThat(result.outcome()).isEqualTo("ANSWERED");
		}
	}

	@Test
	void acceptsContextRecallFromPreviouslyVerifiedEvidenceWithoutCurrentTurnEvidence() {
		String decision = """
				{"message":"We discussed ORD-1002.","outcome":"ANSWERED",
				"resolvedIntents":["context recall"],"orderId":"ORD-1002","evidence":[],
				"recommendations":[],"actionsRequiringApproval":[]}
				""";
		List<ConversationTurn> history = List.of(new ConversationTurn(
				"Where is ORD-1002?", "ORD-1002 is awaiting inventory refresh.", List.of(
						new ExecutedToolCall("orders", GetCustomerOrdersTool.NAME,
								"verified ownership", true, null))));

		AgentDecision result = validator.parseAndValidate(decision,
				"Which order did we just discuss?", history, List.of());

		assertThat(result.outcome()).isEqualTo("ANSWERED");
		assertThat(result.orderId()).isEqualTo("ORD-1002");
	}
}
