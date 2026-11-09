package com.example.demo_agent.agent;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetDeliveryStatusTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicToolPathPolicyTest {

	private final DeterministicToolPathPolicy policy = new DeterministicToolPathPolicy();

	@Test
	void listOrdersUsesCustomerOrdersOnly() {
		assertPath("Show me my orders", GetCustomerOrdersTool.NAME);
	}

	@Test
	void identifiedDeliveryUsesOwnershipThenDeliveryStatus() {
		assertPath("Where is ORD-1001?", GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME);
	}

	@Test
	void identifiedRefundUsesOwnershipDeliveryStatusThenRefundPolicy() {
		assertPath("Can I cancel ORD-1002 and get a refund?", GetCustomerOrdersTool.NAME,
				GetDeliveryStatusTool.NAME, GetRefundPolicyTool.NAME);
	}

	@Test
	void identifiedExchangeUsesOwnershipDeliveryStatusThenExchangePolicy() {
		assertPath("Can I exchange ORD-1001?", GetCustomerOrdersTool.NAME,
				GetDeliveryStatusTool.NAME, GetExchangePolicyTool.NAME);
	}

	@Test
	void standaloneRefundPolicyChecksOrdersThenUsesRefundPolicy() {
		assertPath("What is the refund policy?", GetCustomerOrdersTool.NAME, GetRefundPolicyTool.NAME);
	}

	@Test
	void standaloneExchangePolicyChecksOrdersThenUsesExchangePolicy() {
		assertPath("What are the exchange rules?", GetCustomerOrdersTool.NAME, GetExchangePolicyTool.NAME);
	}

	@Test
	void combinedRefundAndExchangeQuestionIsNotForcedIntoOnePath() {
		assertThat(policy.expectedPath("What are the refund and exchange policies?")).isEmpty();
	}

	@Test
	void followUpRefundReusesVerifiedOwnershipAndDeliveryEvidence() {
		List<ConversationTurn> history = verifiedDeliveryHistory();

		assertThat(policy.expectedPath("Can I get a refund for it?", history))
				.hasValueSatisfying(path -> assertThat(path.toolNames())
						.containsExactly(GetRefundPolicyTool.NAME));
	}

	@Test
	void contextRecallRequiresNoToolsAndRejectsAnUnnecessaryLookup() {
		List<ConversationTurn> history = verifiedDeliveryHistory();
		String message = "Which order did we just discuss?";

		assertThat(policy.expectedPath(message, history))
				.hasValueSatisfying(path -> assertThat(path.toolNames()).isEmpty());
		assertThatThrownBy(() -> policy.validateProposed(message, history, List.of(),
				List.of(new RequestedToolCall("orders", GetCustomerOrdersTool.NAME,
						"{\"customerId\":\"CUST-1001\"}"))))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("CONTEXT_RECALL");
	}

	@Test
	void rejectsAToolCallThatIsOutOfOrder() {
		RequestedToolCall delivery = new RequestedToolCall("call-1", GetDeliveryStatusTool.NAME,
				"{\"orderId\":\"ORD-1001\"}");

		assertThatThrownBy(() -> policy.validateProposed("Where is ORD-1001?", List.of(), List.of(delivery)))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("get_customer_orders")
				.hasMessageContaining("get_delivery_status");
	}

	@Test
	void rejectsAFinalDecisionBeforeTheRequiredPathCompletes() {
		LocalDateTime timestamp = LocalDateTime.of(2026, 11, 8, 12, 0);
		CustomerOrderResponse unfulfilled = new CustomerOrderResponse("CUST-1001", "ORD-1001", timestamp,
				new OrderStatusResponse("OUT_FOR_DELIVERY", "Out for delivery", "test", timestamp, false));
		ExecutedToolCall ownership = new ExecutedToolCall("call-1", GetCustomerOrdersTool.NAME,
				List.of(unfulfilled), true, null);

		assertThatThrownBy(() -> policy.validateComplete("Where is ORD-1001?", List.of(ownership)))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("must complete");
	}

	@Test
	void deliveredOnlyEvidenceCompletesAnExchangePathWithoutAnotherDataTool() {
		LocalDateTime timestamp = LocalDateTime.of(2026, 11, 5, 15, 0);
		CustomerOrderResponse delivered = new CustomerOrderResponse("CUST-1004", "ORD-1006",
				timestamp.minusDays(3), new OrderStatusResponse(
						"DELIVERED", "Delivered", "Delivered", timestamp, true));
		ExecutedToolCall ownership = new ExecutedToolCall("call-orders", GetCustomerOrdersTool.NAME,
				List.of(delivered), true, null);

		assertThat(policy.nextRequiredTool("Can I exchange ORD-1006?", List.of(), List.of(ownership)))
				.isEmpty();
	}

	private void assertPath(String message, String... expectedTools) {
		assertThat(policy.expectedPath(message)).hasValueSatisfying(path ->
				assertThat(path.toolNames()).containsExactly(expectedTools));
	}

	private List<ConversationTurn> verifiedDeliveryHistory() {
		return List.of(new ConversationTurn("Where is ORD-1002?",
				"ORD-1002 is awaiting inventory refresh.", List.of(
						new ExecutedToolCall("orders", GetCustomerOrdersTool.NAME, "verified", true, null),
						new ExecutedToolCall("delivery", GetDeliveryStatusTool.NAME, "verified", true, null))));
	}
}
