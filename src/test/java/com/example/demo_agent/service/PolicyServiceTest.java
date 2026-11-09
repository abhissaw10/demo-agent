package com.example.demo_agent.service;

import com.example.demo_agent.exception.PolicyReadException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyServiceTest {

	@Test
	void getRefundPolicyReturnsMarkdown() {
		PolicyService service = service("# Refund Policy", "# Exchange Policy");

		assertThat(service.getRefundPolicy()).startsWith("# Refund Policy");
	}

	@Test
	void getExchangePolicyReturnsMarkdown() {
		PolicyService service = service("# Refund Policy", "# Exchange Policy");

		assertThat(service.getExchangePolicy()).startsWith("# Exchange Policy");
	}

	@Test
	void unavailablePolicyIsReportedWithoutLeakingIoDetails() throws IOException {
		Resource broken = mock(Resource.class);
		when(broken.getContentAsString(StandardCharsets.UTF_8)).thenThrow(new IOException("disk detail"));
		PolicyService service = new PolicyService(broken, resource("# Exchange Policy"));

		assertThatThrownBy(service::getRefundPolicy)
				.isInstanceOf(PolicyReadException.class)
				.hasMessage("The refund policy is temporarily unavailable.");
	}

	private static PolicyService service(String refund, String exchange) {
		return new PolicyService(resource(refund), resource(exchange));
	}

	private static Resource resource(String content) {
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}
}
