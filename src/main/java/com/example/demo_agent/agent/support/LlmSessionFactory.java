package com.example.demo_agent.agent.support;

import com.example.demo_agent.agent.LlmSession;

import java.util.List;

public interface LlmSessionFactory {

	LlmSession create(List<ConversationTurn> history, String customerId, String message);
}
