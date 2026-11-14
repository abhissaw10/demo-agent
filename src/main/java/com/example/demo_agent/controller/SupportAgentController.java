package com.example.demo_agent.controller;

import com.example.demo_agent.agent.support.SupportAgentService;
import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.tracing.CorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportAgentController {

	private final SupportAgentService supportAgentService;

	public SupportAgentController(SupportAgentService supportAgentService) {
		this.supportAgentService = supportAgentService;
	}

	@PostMapping("/chat")
	public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
			HttpServletRequest httpRequest) {
		Object sessionId = httpRequest.getAttribute(CorrelationFilter.SESSION_ID_ATTRIBUTE);
		return ResponseEntity.ok(supportAgentService.chat(request,
				sessionId == null ? "anonymous" : sessionId.toString()));
	}
}
