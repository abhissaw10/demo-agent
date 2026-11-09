package com.example.demo_agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "support")
public record SupportAgentProperties(OrderApi orderApi, ChatApi chatApi, Llm llm, Agent agent) {

	public record OrderApi(String baseUrl, Duration connectTimeout, Duration readTimeout) {
	}

	public record ChatApi(String baseUrl, Duration connectTimeout, Duration readTimeout) {
	}

	public record Llm(String apiKey, String baseUrl, String model, Duration timeout, int maxRetries) {
		public boolean configured() {
			return apiKey != null && !apiKey.isBlank();
		}
	}

	public record Agent(int maxIterations, int maxToolCalls, int maxValidationRetries, int historyTurns) {
	}
}
