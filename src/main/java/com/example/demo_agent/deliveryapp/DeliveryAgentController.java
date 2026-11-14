package com.example.demo_agent.deliveryapp;

import com.example.demo_agent.agent.AgentInvocationContext;
import com.example.demo_agent.agent.AgentInvocationHeaders;
import com.example.demo_agent.agent.delivery.DeliveryAgent;
import com.example.demo_agent.agent.delivery.DeliveryAgentEndpoints;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationRequest;
import com.example.demo_agent.agent.delivery.DeliveryInvestigationResult;
import com.example.demo_agent.tracing.CorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class DeliveryAgentController {

	private final DeliveryAgent deliveryAgent;

	public DeliveryAgentController(DeliveryAgent deliveryAgent) {
		this.deliveryAgent = deliveryAgent;
	}

	@PostMapping(DeliveryAgentEndpoints.INVESTIGATIONS)
	public ResponseEntity<DeliveryInvestigationResult> investigate(
			@RequestBody DeliveryInvestigationRequest request, HttpServletRequest httpRequest) {
		AgentInvocationContext context = new AgentInvocationContext(
				parentRunId(httpRequest.getHeader(AgentInvocationHeaders.PARENT_RUN_ID)),
				attribute(httpRequest, CorrelationFilter.SESSION_ID_ATTRIBUTE),
				attribute(httpRequest, CorrelationFilter.TRACE_ID_ATTRIBUTE),
				attribute(httpRequest, CorrelationFilter.SPAN_ID_ATTRIBUTE), Instant.now());
		return ResponseEntity.ok(deliveryAgent.investigate(request, context));
	}

	private String attribute(HttpServletRequest request, String name) {
		Object value = request.getAttribute(name);
		return value == null ? null : value.toString();
	}

	private UUID parentRunId(String value) {
		try {
			return value == null ? null : UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
