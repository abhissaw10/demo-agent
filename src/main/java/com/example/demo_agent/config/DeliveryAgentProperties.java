package com.example.demo_agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "delivery.agent")
public record DeliveryAgentProperties(
		int maxIterations,
		int maxToolCalls,
		int maxValidationRetries) {
}
