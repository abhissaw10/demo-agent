package com.example.demo_agent.tool;

import com.example.demo_agent.client.OrderApiClient;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import org.springframework.stereotype.Component;

@Component
public class GetRefundPolicyTool {

	public static final String NAME = "get_refund_policy";

	private final OrderApiClient orderApiClient;

	public GetRefundPolicyTool(OrderApiClient orderApiClient) {
		this.orderApiClient = orderApiClient;
	}

	public PolicyToolResult execute(Arguments arguments, ToolExecutionContext context) {
		return new PolicyToolResult("refund", orderApiClient.getRefundPolicy());
	}

	@JsonClassDescription("Read the authoritative refund policy. This tool is read-only and never issues money.")
	public static final class Arguments {
	}

	public record PolicyToolResult(String policyType, String policyText) {
	}
}
