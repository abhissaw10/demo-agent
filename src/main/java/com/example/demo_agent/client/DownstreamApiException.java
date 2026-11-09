package com.example.demo_agent.client;

public class DownstreamApiException extends RuntimeException {

	private final boolean rateLimited;
	private final boolean retryable;
	private final String code;

	public DownstreamApiException(String code, String message, boolean rateLimited, boolean retryable,
			Throwable cause) {
		super(message, cause);
		this.code = code;
		this.rateLimited = rateLimited;
		this.retryable = retryable;
	}

	public boolean isRateLimited() {
		return rateLimited;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public String getCode() {
		return code;
	}
}
