package com.example.demo_agent.agent.support;

import com.example.demo_agent.config.SupportAgentProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationMemoryStore {

	private static final int MAX_CONVERSATIONS = 1_000;

	private final ConcurrentHashMap<String, Deque<ConversationTurn>> conversations = new ConcurrentHashMap<>();
	private final int maxTurns;

	public ConversationMemoryStore(SupportAgentProperties properties) {
		this.maxTurns = Math.max(1, properties.agent().historyTurns());
	}

	public List<ConversationTurn> get(String sessionId, String customerId) {
		Deque<ConversationTurn> turns = conversations.get(key(sessionId, customerId));
		if (turns == null) {
			return List.of();
		}
		synchronized (turns) {
			return List.copyOf(new ArrayList<>(turns));
		}
	}

	public void add(String sessionId, String customerId, ConversationTurn turn) {
		if (conversations.size() >= MAX_CONVERSATIONS) {
			conversations.keys().asIterator().forEachRemaining(key -> {
				if (conversations.size() >= MAX_CONVERSATIONS) {
					conversations.remove(key);
				}
			});
		}
		Deque<ConversationTurn> turns = conversations.computeIfAbsent(key(sessionId, customerId),
				ignored -> new ArrayDeque<>());
		synchronized (turns) {
			turns.addLast(turn);
			while (turns.size() > maxTurns) {
				turns.removeFirst();
			}
		}
	}

	private String key(String sessionId, String customerId) {
		return sessionId + "\u0000" + customerId;
	}
}
