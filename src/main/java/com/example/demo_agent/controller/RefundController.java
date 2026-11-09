package com.example.demo_agent.controller;

import com.example.demo_agent.service.PolicyService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/policies")
public class RefundController {

	private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

	private final PolicyService policyService;

	public RefundController(PolicyService policyService) {
		this.policyService = policyService;
	}

	@GetMapping(value = "/refund", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> getRefundPolicy() {
		return ResponseEntity.ok().contentType(MARKDOWN).body(policyService.getRefundPolicy());
	}

	@GetMapping(value = "/exchange", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> getExchangePolicy() {
		return ResponseEntity.ok().contentType(MARKDOWN).body(policyService.getExchangePolicy());
	}
}
