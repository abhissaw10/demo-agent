package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.DecisionValidationException;
import com.example.demo_agent.agent.ExecutedToolCall;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.tool.GetCustomerOrdersTool;
import com.example.demo_agent.tool.GetExchangePolicyTool;
import com.example.demo_agent.tool.GetRefundPolicyTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentDecisionValidator {

	private static final Set<String> OUTCOMES = Set.of("ANSWERED", "CLARIFICATION_REQUIRED", "SAFE_FAILURE");
	private static final Pattern ORDER_ID = Pattern.compile("\\bORD-[A-Za-z0-9-]+\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*");
	private static final int MAX_FOLLOW_UP_WORDS = 50;
	private static final int REPEATED_SEQUENCE_WORDS = 8;
	private final DeterministicToolPathPolicy toolPathPolicy = new DeterministicToolPathPolicy();

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

	public AgentDecision parseAndValidate(String json, String currentMessage, List<ConversationTurn> history,
			List<ExecutedToolCall> evidence) {
		return parseAndValidate(json, currentMessage, history, evidence, null);
	}

	public AgentDecision parseAndValidate(String json, String currentMessage, List<ConversationTurn> history,
			List<ExecutedToolCall> evidence, DeliveryInvestigationResult deliveryInvestigation) {
		if (json == null || json.isBlank()) {
			throw new DecisionValidationException("No structured decision was provided.");
		}
		final AgentDecision decision;
		try {
			decision = objectMapper.readValue(json.trim(), AgentDecision.class);
		} catch (JsonProcessingException exception) {
			throw new DecisionValidationException("The decision is not valid JSON matching the required schema.");
		}
		validateShape(decision, currentMessage, history);
		validateFollowUpStyle(decision, history);

		List<ExecutedToolCall> allEvidence = new ArrayList<>();
		history.stream().flatMap(turn -> turn.verifiedEvidence().stream()).forEach(allEvidence::add);
		allEvidence.addAll(evidence);
		validateDoesNotRequestKnownOrderDate(decision, currentMessage, history, allEvidence);

		Set<String> successfulTools = new HashSet<>();
		Set<String> ownedOrders = new HashSet<>();
		boolean customerOrdersChecked = false;
		boolean noOrders = false;
		boolean onlyDeliveredOrders = false;
		for (ExecutedToolCall call : allEvidence) {
			if (GetCustomerOrdersTool.NAME.equals(call.name())) {
				customerOrdersChecked = true;
				if (call.success() && call.output() instanceof List<?> values) {
					noOrders = values.isEmpty();
					onlyDeliveredOrders = !values.isEmpty()
							&& values.stream().allMatch(CustomerOrderResponse.class::isInstance)
							&& values.stream().map(CustomerOrderResponse.class::cast)
									.allMatch(order -> order.overallStatus() != null
											&& "DELIVERED".equals(order.overallStatus().code()));
				} else if (!call.success() && call.output() instanceof Map<?, ?> error) {
					noOrders = "CUSTOMER_ORDERS_NOT_FOUND".equals(error.get("error"));
				}
			}
			if (!call.success()) {
				continue;
			}
			successfulTools.add(call.name());
			if (GetCustomerOrdersTool.NAME.equals(call.name()) && call.output() instanceof List<?> values) {
				values.stream().filter(CustomerOrderResponse.class::isInstance)
						.map(CustomerOrderResponse.class::cast)
						.map(CustomerOrderResponse::orderId).forEach(ownedOrders::add);
			}
		}

		String lower = currentMessage.toLowerCase(Locale.ROOT);
		if (mentions(lower, "refund", "money back") && "ANSWERED".equals(decision.outcome())
				&& !successfulTools.contains(GetRefundPolicyTool.NAME)
				&& !(customerOrdersChecked && (noOrders || onlyDeliveredOrders))) {
			throw new DecisionValidationException("A refund answer requires verified refund policy evidence.");
		}
		if (mentions(lower, "exchange", "replace", "replacement") && "ANSWERED".equals(decision.outcome())
				&& !successfulTools.contains(GetExchangePolicyTool.NAME)
				&& !(customerOrdersChecked && (noOrders || onlyDeliveredOrders))) {
			throw new DecisionValidationException("An exchange answer requires verified exchange policy evidence.");
		}
		boolean deliveryIntent = mentions(lower, "delivery", "delivered", "tracking", "where is", "status");
		boolean deliveryRequired = toolPathPolicy.expectedPath(currentMessage, history)
				.map(DeterministicToolPathPolicy.ExpectedToolPath::useCase)
				.filter(useCase -> Set.of("DELIVERY_STATUS", "REFUND_ELIGIBILITY",
						"EXCHANGE_ELIGIBILITY", "CONTEXTUAL_REFUND_ELIGIBILITY",
						"CONTEXTUAL_EXCHANGE_ELIGIBILITY").contains(useCase))
				.isPresent();
		boolean deliveredPolicyShortcut = onlyDeliveredOrders
				&& mentions(lower, "refund", "money back", "exchange", "replace", "replacement");
		if ((deliveryIntent || deliveryRequired) && "ANSWERED".equals(decision.outcome())
				&& deliveryInvestigation == null && !noOrders && !deliveredPolicyShortcut) {
			throw new DecisionValidationException(
					"This answer requires a structured result from the Delivery Agent HTTP endpoint.");
		}
		if (deliveryInvestigation != null && decision.orderId() != null
				&& !decision.orderId().equals(deliveryInvestigation.orderId())) {
			throw new DecisionValidationException(
					"The response orderId does not match the Delivery Agent result.");
		}
		if (decision.orderId() != null && !ownedOrders.isEmpty() && !ownedOrders.contains(decision.orderId())) {
			throw new DecisionValidationException("The proposed order is not in the verified owned-order set.");
		}
		if (deliveryIntent && ownedOrders.size() > 1 && !conversationNamesAnOrder(currentMessage, history)
				&& !"CLARIFICATION_REQUIRED".equals(decision.outcome())) {
			throw new DecisionValidationException("Multiple orders match and no order was identified; clarification is required.");
		}
		return decision;
	}

	private void validateDoesNotRequestKnownOrderDate(AgentDecision decision, String currentMessage,
			List<ConversationTurn> history, List<ExecutedToolCall> evidence) {
		String proposedMessage = decision.message().toLowerCase(Locale.ROOT);
		boolean asksForPlacedDate = mentions(proposedMessage, "when did", "when you placed",
				"when was", "order date", "placed date", "purchase date")
				&& mentions(proposedMessage, "order", "placed", "purchased");
		if (!asksForPlacedDate) {
			return;
		}
		Set<String> referencedOrders = referencedOrderIds(currentMessage, history);
		boolean dateIsKnown = evidence.stream()
				.filter(ExecutedToolCall::success)
				.filter(call -> GetCustomerOrdersTool.NAME.equals(call.name()))
				.map(ExecutedToolCall::output)
				.filter(List.class::isInstance)
				.map(value -> (List<?>) value)
				.flatMap(List::stream)
				.filter(CustomerOrderResponse.class::isInstance)
				.map(CustomerOrderResponse.class::cast)
				.anyMatch(order -> order.orderStartDate() != null
						&& (referencedOrders.isEmpty() || referencedOrders.contains(order.orderId())));
		if (dateIsKnown) {
			throw new DecisionValidationException(
					"The order placement date is already present in verified order data; use it instead of asking the customer.");
		}
	}

	private Set<String> referencedOrderIds(String currentMessage, List<ConversationTurn> history) {
		Set<String> orderIds = new HashSet<>();
		Matcher current = ORDER_ID.matcher(currentMessage);
		while (current.find()) {
			orderIds.add(current.group().toUpperCase(Locale.ROOT));
		}
		for (int index = history.size() - 1; index >= 0 && orderIds.isEmpty(); index--) {
			ConversationTurn turn = history.get(index);
			Matcher prior = ORDER_ID.matcher(turn.userMessage() + " " + turn.assistantMessage());
			while (prior.find()) {
				orderIds.add(prior.group().toUpperCase(Locale.ROOT));
			}
		}
		return orderIds;
	}

	private void validateFollowUpStyle(AgentDecision decision, List<ConversationTurn> history) {
		if (history.isEmpty()) {
			return;
		}
		List<String> messageWords = words(decision.message());
		if (messageWords.size() > MAX_FOLLOW_UP_WORDS) {
			throw new DecisionValidationException(
					"A follow-up message must be no more than " + MAX_FOLLOW_UP_WORDS + " words.");
		}
		for (ConversationTurn turn : history) {
			if (containsRepeatedSequence(messageWords, words(turn.assistantMessage()))) {
				throw new DecisionValidationException(
						"The follow-up repeats information already communicated; answer only the new intent.");
			}
		}
	}

	private List<String> words(String text) {
		List<String> words = new ArrayList<>();
		Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (matcher.find()) {
			words.add(matcher.group());
		}
		return words;
	}

	private boolean containsRepeatedSequence(List<String> messageWords, List<String> priorWords) {
		if (messageWords.size() < REPEATED_SEQUENCE_WORDS || priorWords.size() < REPEATED_SEQUENCE_WORDS) {
			return false;
		}
		Set<String> priorSequences = new HashSet<>();
		for (int index = 0; index <= priorWords.size() - REPEATED_SEQUENCE_WORDS; index++) {
			priorSequences.add(String.join("\u0000",
					priorWords.subList(index, index + REPEATED_SEQUENCE_WORDS)));
		}
		for (int index = 0; index <= messageWords.size() - REPEATED_SEQUENCE_WORDS; index++) {
			String sequence = String.join("\u0000",
					messageWords.subList(index, index + REPEATED_SEQUENCE_WORDS));
			if (priorSequences.contains(sequence)) {
				return true;
			}
		}
		return false;
	}

	private void validateShape(AgentDecision decision, String currentMessage,
			List<ConversationTurn> history) {
		if (decision.message() == null || decision.message().isBlank()) {
			throw new DecisionValidationException("message must not be blank.");
		}
		if (decision.message().trim().equalsIgnoreCase(currentMessage.trim())) {
			throw new DecisionValidationException("The final answer cannot merely echo the customer's question.");
		}
		if (!OUTCOMES.contains(decision.outcome())) {
			throw new DecisionValidationException("outcome is invalid.");
		}
		if (decision.resolvedIntents() == null || decision.evidence() == null
				|| decision.recommendations() == null || decision.actionsRequiringApproval() == null) {
			throw new DecisionValidationException("All list fields are required.");
		}
		if ("ANSWERED".equals(decision.outcome()) && decision.evidence().isEmpty()
				&& !canAnswerFromVerifiedHistory(currentMessage, history)) {
			throw new DecisionValidationException("An answered decision must cite observed evidence.");
		}
	}

	private boolean canAnswerFromVerifiedHistory(String currentMessage, List<ConversationTurn> history) {
		boolean hasVerifiedEvidence = history.stream()
				.flatMap(turn -> turn.verifiedEvidence().stream())
				.anyMatch(ExecutedToolCall::success);
		return hasVerifiedEvidence && toolPathPolicy.expectedPath(currentMessage, history)
				.filter(path -> path.toolNames().isEmpty())
				.isPresent();
	}

	private boolean conversationNamesAnOrder(String message, List<ConversationTurn> history) {
		if (ORDER_ID.matcher(message).find()) {
			return true;
		}
		return history.stream().anyMatch(turn -> ORDER_ID.matcher(turn.userMessage()).find());
	}

	private boolean mentions(String text, String... terms) {
		for (String term : terms) {
			if (text.contains(term)) {
				return true;
			}
		}
		return false;
	}
}
