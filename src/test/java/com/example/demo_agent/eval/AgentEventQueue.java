package com.example.demo_agent.eval;

import com.example.demo_agent.tracing.AgentRunCompletedEvent;
import com.example.demo_agent.tracing.AgentRunFailedEvent;
import com.example.demo_agent.tracing.AgentRunStartedEvent;
import com.example.demo_agent.tracing.DelegatedAgentRunCompletedEvent;
import com.example.demo_agent.tracing.DelegatedAgentRunFailedEvent;
import com.example.demo_agent.tracing.DelegatedAgentRunStartedEvent;
import com.example.demo_agent.tracing.LlmCallEvent;
import com.example.demo_agent.tracing.ResourceCallEvent;
import com.example.demo_agent.tracing.ToolCallEvent;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

final class AgentEventQueue {

	private static final int CAPACITY = 10_000;
	private final LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>(CAPACITY);

	@EventListener
	public void onRunStarted(AgentRunStartedEvent event) {
		add(event);
	}

	@EventListener
	public void onRunCompleted(AgentRunCompletedEvent event) {
		add(event);
	}

	@EventListener
	public void onRunFailed(AgentRunFailedEvent event) {
		add(event);
	}

	@EventListener
	public void onDelegatedRunStarted(DelegatedAgentRunStartedEvent event) {
		add(event);
	}

	@EventListener
	public void onDelegatedRunCompleted(DelegatedAgentRunCompletedEvent event) {
		add(event);
	}

	@EventListener
	public void onDelegatedRunFailed(DelegatedAgentRunFailedEvent event) {
		add(event);
	}

	@EventListener
	public void onLlmCall(LlmCallEvent event) {
		add(event);
	}

	@EventListener
	public void onToolCall(ToolCallEvent event) {
		add(event);
	}

	@EventListener
	public void onResourceCall(ResourceCallEvent event) {
		add(event);
	}

	public void clear() {
		events.clear();
	}

	public List<Object> drain() {
		List<Object> drained = new ArrayList<>();
		events.drainTo(drained);
		return List.copyOf(drained);
	}

	private void add(Object event) {
		if (!events.offer(event)) {
			throw new IllegalStateException("Live eval event queue exceeded capacity " + CAPACITY + ".");
		}
	}
}
