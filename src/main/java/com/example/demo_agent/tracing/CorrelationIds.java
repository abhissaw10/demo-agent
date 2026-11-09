package com.example.demo_agent.tracing;

import java.util.UUID;

public final class CorrelationIds {

	private CorrelationIds() {
	}

	public static String newTraceId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	public static String newSpanId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
	}
}
