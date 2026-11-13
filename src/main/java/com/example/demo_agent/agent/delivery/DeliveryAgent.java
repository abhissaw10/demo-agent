package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.agent.AgentInvocationContext;

public interface DeliveryAgent {

	DeliveryInvestigationResult investigate(
			DeliveryInvestigationRequest request,
			AgentInvocationContext context);
}
