package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.LlmSession;

interface DeliveryLlmSessionFactory {

	LlmSession create(DeliveryInvestigationRequest request, AgentInvocationContext context);

	default String model() {
		return "delivery-model";
	}
}
