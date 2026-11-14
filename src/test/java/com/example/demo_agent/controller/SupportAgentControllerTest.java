package com.example.demo_agent.controller;

import com.example.demo_agent.agent.support.SupportAgentService;
import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.exception.GlobalExceptionHandler;
import com.example.demo_agent.tracing.CorrelationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupportAgentControllerTest {

	private SupportAgentService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(SupportAgentService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new SupportAgentController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilter(new CorrelationFilter())
				.build();
	}

	@Test
	void chatReturnsStructuredResponseAndUsesSession() throws Exception {
		ChatResponse response = new ChatResponse("Order ORD-1001 was delivered.", "ANSWERED",
				List.of("delivery status"), "ORD-1001", List.of("Carrier confirms delivery."),
				List.of(), List.of(), 2, 2);
		when(service.chat(any(ChatRequest.class), eq("chat-session"))).thenReturn(response);

		mockMvc.perform(post("/chat")
					.header(CorrelationFilter.SESSION_ID_HEADER, "chat-session")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"message\":\"Where is ORD-1001?\",\"customerId\":\"CUST-1001\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("ANSWERED"))
				.andExpect(jsonPath("$.orderId").value("ORD-1001"))
				.andExpect(jsonPath("$.llmCalls").value(2));
	}

	@Test
	void chatRejectsBlankInput() throws Exception {
		mockMvc.perform(post("/chat")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"message\":\" \",\"customerId\":\"CUST-1001\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}
}
