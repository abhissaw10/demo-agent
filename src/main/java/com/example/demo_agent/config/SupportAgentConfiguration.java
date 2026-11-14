package com.example.demo_agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SupportAgentProperties.class, DeliveryAgentApiProperties.class,
		RuntimeEvalProperties.class})
public class SupportAgentConfiguration {
}
