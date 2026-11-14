package com.example.demo_agent.deliveryapp;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.AgentInvocationHeaders;
import com.example.demo_agent.agent.delivery.DeliveryAgent;
import com.example.demo_agent.agent.delivery.DeliveryAgentEndpoints;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationOutcome;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.exception.GlobalExceptionHandler;
import com.example.demo_agent.tracing.CorrelationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryAgentControllerTest {

	private DeliveryAgent deliveryAgent;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		deliveryAgent = mock(DeliveryAgent.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new DeliveryAgentController(deliveryAgent))
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilter(new CorrelationFilter())
				.build();
	}

	@Test
	void exposesTheExistingAgentContractAndBuildsInvocationContextFromHeaders() throws Exception {
		UUID parentRunId = UUID.randomUUID();
		when(deliveryAgent.investigate(any(), any())).thenReturn(new DeliveryInvestigationResult(
				DeliveryInvestigationOutcome.COMPLETED, "ORD-1002", "IN_TRANSIT",
				"The shipment is in transit.", List.of()));

		mockMvc.perform(post(DeliveryAgentEndpoints.INVESTIGATIONS)
					.header(CorrelationFilter.TRACE_ID_HEADER, "trace-1")
					.header(CorrelationFilter.SPAN_ID_HEADER, "support-resource-span")
					.header(CorrelationFilter.SESSION_ID_HEADER, "session-1")
					.header(AgentInvocationHeaders.PARENT_RUN_ID, parentRunId.toString())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"customerId":"CUST-1001","orderId":"ORD-1002",
							"investigationType":"CURRENT_STATUS"}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("COMPLETED"))
				.andExpect(jsonPath("$.orderId").value("ORD-1002"));

		ArgumentCaptor<DeliveryInvestigationRequest> request =
				ArgumentCaptor.forClass(DeliveryInvestigationRequest.class);
		ArgumentCaptor<AgentInvocationContext> context =
				ArgumentCaptor.forClass(AgentInvocationContext.class);
		verify(deliveryAgent).investigate(request.capture(), context.capture());
		assertThat(request.getValue().customerId()).isEqualTo("CUST-1001");
		assertThat(context.getValue().parentRunId()).isEqualTo(parentRunId);
		assertThat(context.getValue().sessionId()).isEqualTo("session-1");
		assertThat(context.getValue().traceId()).isEqualTo("trace-1");
		assertThat(context.getValue().parentSpanId()).isEqualTo("support-resource-span");
	}
}
