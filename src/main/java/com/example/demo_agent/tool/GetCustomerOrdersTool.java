package com.example.demo_agent.tool;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.client.OrderApiClient;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetCustomerOrdersTool {

	public static final String NAME = "get_customer_orders";

	private final OrderApiClient orderApiClient;

	public GetCustomerOrdersTool(OrderApiClient orderApiClient) {
		this.orderApiClient = orderApiClient;
	}

	public List<CustomerOrderResponse> execute(Arguments arguments, ToolExecutionContext context) {
		if (arguments == null || arguments.customerId == null || arguments.customerId.isBlank()) {
			throw new ToolValidationException("customerId is required.");
		}
		if (!context.customerId().equals(arguments.customerId.trim())) {
			throw new ToolValidationException("The requested customerId does not match the authenticated customer.");
		}
		return orderApiClient.getCustomerOrders(context.customerId());
	}

	@JsonClassDescription("List every order owned by the current customer. Use this before resolving an unspecified order.")
	public static final class Arguments {
		@JsonPropertyDescription("The current customer's exact customer ID.")
		public String customerId;
	}
}
