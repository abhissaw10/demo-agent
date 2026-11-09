package com.example.demo_agent.agent;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetDeliveryStatusTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.regex.Pattern;

public final class DeterministicToolPathPolicy {

	private static final Pattern ORDER_ID = Pattern.compile("\\bORD-[A-Za-z0-9-]+\\b", Pattern.CASE_INSENSITIVE);

	public Optional<ExpectedToolPath> expectedPath(String message) {
		return expectedPath(message, List.of());
	}

	public Optional<ExpectedToolPath> expectedPath(String message, List<ConversationTurn> history) {
		String normalized = message.toLowerCase(Locale.ROOT);
		boolean namesOrder = ORDER_ID.matcher(message).find();
		boolean refund = mentions(normalized, "refund", "money back", "cancel");
		boolean exchange = mentions(normalized, "exchange", "replace", "replacement");
		boolean delivery = mentions(normalized, "delivery", "delivered", "tracking", "where is", "status");
		boolean policy = mentions(normalized, "policy", "rule", "rules");

		if (refund && exchange) {
			return Optional.empty();
		}
		if (!history.isEmpty() && asksOnlyForKnownContext(normalized)
				&& hasPriorEvidence(history, GetCustomerOrdersTool.NAME)) {
			return Optional.of(new ExpectedToolPath("CONTEXT_RECALL", List.of()));
		}
		if (!namesOrder && refund && hasPriorEvidence(history,
				GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME)) {
			return Optional.of(new ExpectedToolPath("CONTEXTUAL_REFUND_ELIGIBILITY",
					List.of(GetRefundPolicyTool.NAME)));
		}
		if (!namesOrder && exchange && hasPriorEvidence(history,
				GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME)) {
			return Optional.of(new ExpectedToolPath("CONTEXTUAL_EXCHANGE_ELIGIBILITY",
					List.of(GetExchangePolicyTool.NAME)));
		}
		if (!namesOrder && delivery && hasPriorEvidence(history, GetDeliveryStatusTool.NAME)
				&& refersToPriorResult(normalized)) {
			return Optional.of(new ExpectedToolPath("CONTEXTUAL_DELIVERY_RECALL", List.of()));
		}
		if (namesOrder && refund) {
			return Optional.of(new ExpectedToolPath("REFUND_ELIGIBILITY",
					List.of(GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME, GetRefundPolicyTool.NAME)));
		}
		if (namesOrder && exchange) {
			return Optional.of(new ExpectedToolPath("EXCHANGE_ELIGIBILITY",
					List.of(GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME, GetExchangePolicyTool.NAME)));
		}
		if (namesOrder && delivery) {
			return Optional.of(new ExpectedToolPath("DELIVERY_STATUS",
					List.of(GetCustomerOrdersTool.NAME, GetDeliveryStatusTool.NAME)));
		}
		if (!namesOrder && refund && policy) {
			return Optional.of(new ExpectedToolPath("REFUND_POLICY",
					List.of(GetCustomerOrdersTool.NAME, GetRefundPolicyTool.NAME)));
		}
		if (!namesOrder && exchange && policy) {
			return Optional.of(new ExpectedToolPath("EXCHANGE_POLICY",
					List.of(GetCustomerOrdersTool.NAME, GetExchangePolicyTool.NAME)));
		}
		if (!refund && !exchange && !delivery && mentions(normalized, "orders", "my order")) {
			return Optional.of(new ExpectedToolPath("LIST_ORDERS", List.of(GetCustomerOrdersTool.NAME)));
		}
		return Optional.empty();
	}

	public void validateProposed(String message, List<ExecutedToolCall> observed,
			List<RequestedToolCall> proposed) {
		validateProposed(message, List.of(), observed, proposed);
	}

	public void validateProposed(String message, List<ConversationTurn> history,
			List<ExecutedToolCall> observed, List<RequestedToolCall> proposed) {
		expectedPath(message, history).ifPresent(path -> {
			List<String> completed = completedToolNames(observed);
			List<String> required = requiredPath(path, observed);
			List<String> proposedNames = proposed.stream().map(RequestedToolCall::name).toList();
			if (proposedNames.size() != 1 || completed.size() >= required.size()
					|| !proposedNames.getFirst().equals(required.get(completed.size()))) {
				throw new DecisionValidationException("The deterministic " + path.useCase()
						+ " tool path requires one step at a time. Required " + required
						+ ", completed " + completed + " and proposed " + proposedNames + ".");
			}
		});
	}

	public void validateComplete(String message, List<ExecutedToolCall> observed) {
		validateComplete(message, List.of(), observed);
	}

	public void validateComplete(String message, List<ConversationTurn> history,
			List<ExecutedToolCall> observed) {
		expectedPath(message, history).ifPresent(path -> {
			List<String> completed = completedToolNames(observed);
			List<String> required = requiredPath(path, observed);
			if (!completed.equals(required)) {
				throw new DecisionValidationException("The deterministic " + path.useCase()
						+ " tool path must complete before the final decision. Expected "
						+ required + " but completed " + completed + ".");
			}
		});
	}

	public Optional<String> nextRequiredTool(String message, List<ConversationTurn> history,
			List<ExecutedToolCall> observed) {
		return expectedPath(message, history).flatMap(path -> {
			List<String> completed = completedToolNames(observed);
			List<String> required = requiredPath(path, observed);
			if (completed.size() >= required.size()) {
				return Optional.empty();
			}
			return Optional.of(required.get(completed.size()));
		});
	}

	public Optional<AgentDecision> earlyDecision(String message, List<ConversationTurn> history,
			List<ExecutedToolCall> observed) {
		Optional<ExpectedToolPath> expected = expectedPath(message, history);
		if (expected.isEmpty()) {
			return Optional.empty();
		}
		if (hasNoOrders(observed)) {
			return Optional.of(new AgentDecision(
					"No orders were found for this customer.", "ANSWERED",
					List.of(intentName(expected.get().useCase())), null,
					List.of("The customer order lookup found no orders."),
					List.of(), List.of()));
		}
		if (isRefundOrExchange(expected.get().useCase()) && hasOnlyFulfilledOrders(observed)) {
			String orderId = singleOwnedOrderId(observed).orElse(null);
			String subject = orderId == null ? "Every owned order" : orderId;
			return Optional.of(new AgentDecision(
					subject + " is already delivered, so no unfulfilled order requires a "
							+ policyName(expected.get().useCase()) + " policy check.",
					"ANSWERED", List.of(intentName(expected.get().useCase())), orderId,
					List.of("The customer order lookup shows that every owned order is delivered."),
					List.of(), List.of()));
		}
		return Optional.empty();
	}

	private List<String> requiredPath(ExpectedToolPath path, List<ExecutedToolCall> observed) {
		if (hasNoOrders(observed)
				|| (isRefundOrExchange(path.useCase()) && hasOnlyFulfilledOrders(observed))) {
			return List.of(GetCustomerOrdersTool.NAME);
		}
		return path.toolNames();
	}

	private List<String> completedToolNames(List<ExecutedToolCall> observed) {
		return observed.stream()
				.filter(call -> call.success() || isNoOrdersResult(call))
				.map(ExecutedToolCall::name)
				.toList();
	}

	private boolean hasNoOrders(List<ExecutedToolCall> observed) {
		return observed.stream().anyMatch(this::isNoOrdersResult);
	}

	private boolean isNoOrdersResult(ExecutedToolCall call) {
		if (!GetCustomerOrdersTool.NAME.equals(call.name())) {
			return false;
		}
		if (call.success() && call.output() instanceof List<?> orders) {
			return orders.isEmpty();
		}
		return !call.success() && call.output() instanceof Map<?, ?> error
				&& "CUSTOMER_ORDERS_NOT_FOUND".equals(error.get("error"));
	}

	private boolean hasOnlyFulfilledOrders(List<ExecutedToolCall> observed) {
		return observed.stream()
				.filter(call -> GetCustomerOrdersTool.NAME.equals(call.name()) && call.success())
				.map(ExecutedToolCall::output)
				.filter(List.class::isInstance)
				.map(value -> (List<?>) value)
				.anyMatch(this::containsOnlyDeliveredOrders);
	}

	private boolean containsOnlyDeliveredOrders(List<?> orders) {
		return !orders.isEmpty() && orders.stream().allMatch(CustomerOrderResponse.class::isInstance)
				&& orders.stream().map(CustomerOrderResponse.class::cast)
						.allMatch(order -> order.overallStatus() != null
								&& "DELIVERED".equals(order.overallStatus().code()));
	}

	private boolean isRefundOrExchange(String useCase) {
		return useCase.startsWith("REFUND_") || useCase.startsWith("EXCHANGE_");
	}

	private String policyName(String useCase) {
		return useCase.startsWith("REFUND_") ? "refund" : "exchange";
	}

	private String intentName(String useCase) {
		return switch (useCase) {
			case "LIST_ORDERS" -> "list orders";
			case "DELIVERY_STATUS" -> "delivery status";
			default -> policyName(useCase) + " eligibility";
		};
	}

	private Optional<String> singleOwnedOrderId(List<ExecutedToolCall> observed) {
		return observed.stream()
				.filter(call -> GetCustomerOrdersTool.NAME.equals(call.name()) && call.success())
				.map(ExecutedToolCall::output)
				.filter(List.class::isInstance)
				.map(value -> (List<?>) value)
				.filter(orders -> orders.size() == 1 && orders.getFirst() instanceof CustomerOrderResponse)
				.map(orders -> ((CustomerOrderResponse) orders.getFirst()).orderId())
				.findFirst();
	}

	private boolean mentions(String text, String... terms) {
		for (String term : terms) {
			if (text.contains(term)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasPriorEvidence(List<ConversationTurn> history, String... toolNames) {
		List<String> successfulTools = history.stream()
				.flatMap(turn -> turn.verifiedEvidence().stream())
				.filter(ExecutedToolCall::success)
				.map(ExecutedToolCall::name)
				.toList();
		return java.util.Arrays.stream(toolNames).allMatch(successfulTools::contains);
	}

	private boolean asksOnlyForKnownContext(String message) {
		return mentions(message, "which order", "what order", "order did we", "order were we")
				&& refersToPriorResult(message);
	}

	private boolean refersToPriorResult(String message) {
		return mentions(message, "just", "discuss", "earlier", "previous", "before", "you found", "you said");
	}

	public record ExpectedToolPath(String useCase, List<String> toolNames) {
	}
}
