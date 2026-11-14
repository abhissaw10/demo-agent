package com.example.demo_agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "support.delivery-agent-api")
public record DeliveryAgentApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
