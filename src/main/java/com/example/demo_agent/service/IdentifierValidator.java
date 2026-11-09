package com.example.demo_agent.service;

import com.example.demo_agent.exception.BadRequestException;

final class IdentifierValidator {

	private IdentifierValidator() {
	}

	static String require(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new BadRequestException(fieldName + " must not be blank.");
		}
		if (value.length() > 64) {
			throw new BadRequestException(fieldName + " must not exceed 64 characters.");
		}
		return value.trim();
	}
}
