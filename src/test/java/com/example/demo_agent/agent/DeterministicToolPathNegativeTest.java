package com.example.demo_agent.agent;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.InvestigateDeliveryTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicToolPathNegativeTest {

	private final DeterministicToolPathPolicy policy = new DeterministicToolPathPolicy();

	@Test
	void noOrdersBlocksDeliveryStatusTool() {
		assertBlockedAfterNoOrders("Where is ORD-1001?", InvestigateDeliveryTool.NAME);
	}

	@Test
	void noOrdersBlocksRefundPolicyTool() {
		assertBlockedAfterNoOrders("Can I refund ORD-1001?", GetRefundPolicyTool.NAME);
	}

	@Test
	void noOrdersBlocksExchangePolicyTool() {
		assertBlockedAfterNoOrders("Can I exchange ORD-1001?", GetExchangePolicyTool.NAME);
	}

	@Test
	void noOrdersAllowsFinalDecisionAfterCustomerOrdersTool() {
		assertThatCode(() -> policy.validateComplete("Where is ORD-1001?", List.of(noOrders())))
				.doesNotThrowAnyException();
	}

	@Test
	void customerOrdersNotFoundErrorAlsoStopsThePath() {
		ExecutedToolCall notFound = new ExecutedToolCall("orders", GetCustomerOrdersTool.NAME,
				Map.of("error", "CUSTOMER_ORDERS_NOT_FOUND"), false, null);

		assertThatCode(() -> policy.validateComplete("Where is ORD-1001?", List.of(notFound)))
				.doesNotThrowAnyException();
	}

	@Test
	void onlyFulfilledOrdersBlocksRefundPolicyTool() {
		assertBlockedAfterFulfilledOrders("What is the refund policy?", GetRefundPolicyTool.NAME);
	}

	@Test
	void onlyFulfilledOrdersBlocksExchangePolicyTool() {
		assertBlockedAfterFulfilledOrders("What are the exchange rules?", GetExchangePolicyTool.NAME);
	}

	@Test
	void mixedFulfilledAndUnfulfilledOrdersStillAllowsRefundPath() {
		ExecutedToolCall orders = orders(List.of(order("ORD-1001", "DELIVERED"),
				order("ORD-1002", "AWAITING_INVENTORY_REFRESH")));
		RequestedToolCall delivery = requested("delivery", InvestigateDeliveryTool.NAME);
		assertThatCode(() -> policy.validateProposed("Can I refund ORD-1002?", List.of(orders), List.of(delivery)))
				.doesNotThrowAnyException();

		ExecutedToolCall deliveryResult = new ExecutedToolCall("delivery", InvestigateDeliveryTool.NAME,
				"verified", true, null);
		RequestedToolCall refund = requested("refund", GetRefundPolicyTool.NAME);
		assertThatCode(() -> policy.validateProposed("Can I refund ORD-1002?",
				List.of(orders, deliveryResult), List.of(refund))).doesNotThrowAnyException();
	}

	@Test
	void dependentToolsCannotBeBatchedBeforeOrdersAreKnown() {
		RequestedToolCall orders = requested("orders", GetCustomerOrdersTool.NAME);
		RequestedToolCall refund = requested("refund", GetRefundPolicyTool.NAME);

		assertThatThrownBy(() -> policy.validateProposed("What is the refund policy?",
				List.of(), List.of(orders, refund)))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("one step at a time");
	}

	private void assertBlockedAfterNoOrders(String message, String toolName) {
		assertThatThrownBy(() -> policy.validateProposed(message, List.of(noOrders()),
				List.of(requested("next", toolName))))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("completed [get_customer_orders]");
	}

	private void assertBlockedAfterFulfilledOrders(String message, String toolName) {
		ExecutedToolCall delivered = orders(List.of(order("ORD-1001", "DELIVERED")));
		assertThatThrownBy(() -> policy.validateProposed(message, List.of(delivered),
				List.of(requested("next", toolName))))
				.isInstanceOf(DecisionValidationException.class)
				.hasMessageContaining("completed [get_customer_orders]");
	}

	private ExecutedToolCall noOrders() {
		return orders(List.of());
	}

	private ExecutedToolCall orders(List<CustomerOrderResponse> orders) {
		return new ExecutedToolCall("orders", GetCustomerOrdersTool.NAME, orders, true, null);
	}

	private RequestedToolCall requested(String id, String name) {
		return new RequestedToolCall(id, name, "{}");
	}

	private CustomerOrderResponse order(String orderId, String statusCode) {
		LocalDateTime timestamp = LocalDateTime.of(2026, 11, 8, 12, 0);
		return new CustomerOrderResponse("CUST-TEST", orderId, timestamp,
				new OrderStatusResponse(statusCode, statusCode, "test", timestamp,
						"DELIVERED".equals(statusCode)));
	}
}
