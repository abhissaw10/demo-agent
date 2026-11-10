package com.example.demo_agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support.runtime-eval")
public record RuntimeEvalProperties(boolean enabled, double sampleRate, int maxIterations,
		int maxResults, int workers, int queueCapacity, String reportDirectory) {
}
