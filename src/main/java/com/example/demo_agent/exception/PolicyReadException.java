package com.example.demo_agent.exception;

import org.springframework.http.HttpStatus;

public class PolicyReadException extends ApiException {

	public PolicyReadException(String policyName, Throwable cause) {
		super(HttpStatus.INTERNAL_SERVER_ERROR, "POLICY_UNAVAILABLE",
				"The " + policyName + " policy is temporarily unavailable.", cause);
	}
}
