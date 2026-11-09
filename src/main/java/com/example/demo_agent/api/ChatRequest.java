package com.example.demo_agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
		@NotBlank @Size(max = 4000) String message,
		@NotBlank @Size(max = 64) String customerId) {
}
