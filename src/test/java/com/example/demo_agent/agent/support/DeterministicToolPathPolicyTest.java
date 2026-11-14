package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.DecisionValidationException;
import com.example.demo_agent.agent.ExecutedToolCall;
import com.example.demo_agent.agent.RequestedToolCall;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicToolPathPolicyTest {

	private final DeterministicToolPathPolicy policy = new DeterministicToolPathPolicy();

	@Test
	void listOrdersUsesCustomerOrdersOnly() {
		assertPath("Show me my orders", GetCustomerOrdersTool.NAME);
	}

	@Test
	void identifiedDeliveryUsesOwnershipThenDeliveryStatus() {
		assertPath("Where is ORD-1001?", GetCustomerOrdersTool.NAME);
	}

	@Test
	void identifiedRefundUsesOwnershipDeliveryStatusThenRefundPolicy() {
		assertPath("Can I cancel ORD-1002 and get a refund?", GetCustomerOrdersTool.NAME,
				GetRefundPolicyTool.NAME);
	}

	@Test
	void identifiedExchangeUsesOwnershipDeliveryStatusThenExchangePolicy() {
		assertPath("Can I exchange ORD-1001?", GetCustomerOrdersTool.NAME,
				GetExchangePolicyTool.NAME);
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
		RequestedToolCall refund = new RequestedToolCall("call-1", GetRefundPolicyTool.NAME, "{}");

		assertThatThrownBy(() -> policy.validateProposed(
				"Can I refund ORD-1001?", List.of(), List.of(refund)))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("get_customer_orders");
	}

	@Test
	void deliveryToolPathCompletesAfterOwnershipBecauseDelegationIsNotATool() {
		ExecutedToolCall ownership = new ExecutedToolCall(
				"call-1", GetCustomerOrdersTool.NAME, List.of(), true, null);

		assertThatCode(() -> policy.validateComplete("Where is ORD-1001?", List.of(ownership)))
				.doesNotThrowAnyException();
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
						new ExecutedToolCall("orders", GetCustomerOrdersTool.NAME, "verified", true, null))));
	}
}
