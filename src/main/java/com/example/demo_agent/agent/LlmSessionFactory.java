package com.example.demo_agent.agent;

import java.util.List;

public interface LlmSessionFactory {

	LlmSession create(List<ConversationTurn> history, String customerId, String message);
}
