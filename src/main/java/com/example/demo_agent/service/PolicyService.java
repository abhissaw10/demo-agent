package com.example.demo_agent.service;

import com.example.demo_agent.exception.PolicyReadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class PolicyService {

	private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

	private final Resource refundPolicy;
	private final Resource exchangePolicy;

	public PolicyService(
			@Value("classpath:policies/refund.md") Resource refundPolicy,
			@Value("classpath:policies/exchange.md") Resource exchangePolicy) {
		this.refundPolicy = refundPolicy;
		this.exchangePolicy = exchangePolicy;
	}

	public String getRefundPolicy() {
		return readPolicy("refund", refundPolicy);
	}

	public String getExchangePolicy() {
		return readPolicy("exchange", exchangePolicy);
	}

	private String readPolicy(String name, Resource policy) {
		try {
			String content = policy.getContentAsString(StandardCharsets.UTF_8);
			log.info("Loaded {} policy", name);
			return content;
		} catch (IOException exception) {
			throw new PolicyReadException(name, exception);
		}
	}
}
