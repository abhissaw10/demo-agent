package com.example.demo_agent.client;

import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.tracing.CorrelationFilter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SupportChatApiClient {

	private final RestClient restClient;

	public SupportChatApiClient(SupportAgentProperties properties) {
		SupportAgentProperties.ChatApi settings = properties.chatApi();
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(settings.connectTimeout());
		requestFactory.setReadTimeout(settings.readTimeout());
		this.restClient = RestClient.builder()
				.baseUrl(settings.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	public ChatResponse chat(ChatRequest request, String sessionId) {
		return restClient.post()
				.uri("/chat")
				.header(CorrelationFilter.SESSION_ID_HEADER, sessionId)
				.body(request)
				.retrieve()
				.body(ChatResponse.class);
	}
}
