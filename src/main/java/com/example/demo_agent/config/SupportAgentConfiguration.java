package com.example.demo_agent.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(SupportAgentProperties.class)
public class SupportAgentConfiguration {

	@Bean
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

	@Bean
	OpenAIClient openAIClient(SupportAgentProperties properties) {
		SupportAgentProperties.Llm llm = properties.llm();
		return OpenAIOkHttpClient.builder()
				.apiKey(llm.configured() ? llm.apiKey() : "not-configured")
				.baseUrl(llm.baseUrl())
				.timeout(llm.timeout())
				.maxRetries(llm.maxRetries())
				.build();
	}
}
