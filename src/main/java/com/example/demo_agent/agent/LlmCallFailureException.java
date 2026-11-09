package com.example.demo_agent.agent;

public class LlmCallFailureException extends RuntimeException {

	private final int providerCallCount;

	public LlmCallFailureException(RuntimeException cause, int providerCallCount) {
		super(cause.getMessage(), cause);
		this.providerCallCount = providerCallCount;
	}

	public int providerCallCount() {
		return providerCallCount;
	}
}
