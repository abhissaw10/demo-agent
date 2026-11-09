package com.example.demo_agent.controller;

import com.example.demo_agent.exception.GlobalExceptionHandler;
import com.example.demo_agent.service.PolicyService;
import com.example.demo_agent.tracing.CorrelationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundControllerTest {

	private PolicyService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(PolicyService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new RefundController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilter(new CorrelationFilter())
				.build();
	}

	@Test
	void getRefundPolicyReturnsMarkdown() throws Exception {
		when(service.getRefundPolicy()).thenReturn("# Refund Policy\n\nRefund details.");

		mockMvc.perform(get("/policies/refund"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("text/markdown;charset=UTF-8"))
				.andExpect(content().string("# Refund Policy\n\nRefund details."))
				.andExpect(header().exists(CorrelationFilter.TRACE_ID_HEADER));
	}

	@Test
	void getExchangePolicyReturnsMarkdown() throws Exception {
		when(service.getExchangePolicy()).thenReturn("# Exchange Policy\n\nExchange details.");

		mockMvc.perform(get("/policies/exchange"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("text/markdown;charset=UTF-8"))
				.andExpect(content().string("# Exchange Policy\n\nExchange details."));
	}
}
