package com.example.demo_agent.tool;

import com.example.demo_agent.agent.DeliveryInvestigator;
import com.example.demo_agent.agent.DeliveryInvestigator.DeliveryFinding;
import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.client.OrderApiClient;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetDeliveryStatusTool {

	public static final String NAME = "get_delivery_status";

	private final OrderApiClient orderApiClient;
	private final DeliveryInvestigator deliveryInvestigator;

	public GetDeliveryStatusTool(OrderApiClient orderApiClient, DeliveryInvestigator deliveryInvestigator) {
		this.orderApiClient = orderApiClient;
		this.deliveryInvestigator = deliveryInvestigator;
	}

	public DeliveryToolResult execute(Arguments arguments, ToolExecutionContext context) {
		if (arguments == null || arguments.orderId == null || arguments.orderId.isBlank()) {
			throw new ToolValidationException("orderId is required.");
		}
		String orderId = arguments.orderId.trim();
		List<CustomerOrderResponse> ownedOrders = orderApiClient.getCustomerOrders(context.customerId());
		boolean owned = ownedOrders.stream().anyMatch(order -> order.orderId().equals(orderId));
		if (!owned) {
			throw new ToolValidationException("The order does not belong to the current customer.");
		}
		OrderDeliveryStatusResponse rawStatus = orderApiClient.getDeliveryStatus(orderId);
		DeliveryFinding finding = deliveryInvestigator.investigate(rawStatus);
		return new DeliveryToolResult(orderId, finding);
	}

	@JsonClassDescription("Get a Delivery Investigator finding for one order owned by the current customer. The finding, not raw tracking history, is authoritative.")
	public static final class Arguments {
		@JsonPropertyDescription("Exact order ID selected from the current customer's owned orders.")
		public String orderId;
	}

	public record DeliveryToolResult(String orderId, DeliveryFinding investigatorFinding) {
	}
}
