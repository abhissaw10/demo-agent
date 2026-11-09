package com.example.demo_agent.tool;

import com.example.demo_agent.client.OrderApiClient;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import org.springframework.stereotype.Component;

@Component
public class GetExchangePolicyTool {

	public static final String NAME = "get_exchange_policy";

	private final OrderApiClient orderApiClient;

	public GetExchangePolicyTool(OrderApiClient orderApiClient) {
		this.orderApiClient = orderApiClient;
	}

	public PolicyToolResult execute(Arguments arguments, ToolExecutionContext context) {
		return new PolicyToolResult("exchange", orderApiClient.getExchangePolicy());
	}

	@JsonClassDescription("Read the authoritative exchange policy. This tool is read-only and never reserves or replaces inventory.")
	public static final class Arguments {
	}

	public record PolicyToolResult(String policyType, String policyText) {
	}
}
