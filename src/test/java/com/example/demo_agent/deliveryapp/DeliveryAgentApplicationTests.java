package com.example.demo_agent.deliveryapp;

import com.example.demo_agent.agent.delivery.DeliveryAgent;
import com.example.demo_agent.agent.support.SupportAgentService;
import com.example.demo_agent.client.DeliveryAgentApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DeliveryAgentApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("delivery")
class DeliveryAgentApplicationTests {

	@Autowired
	private ApplicationContext context;

	@LocalServerPort
	private int port;

	@Test
	void deliveryApplicationContainsDeliveryAgentButNoSupportAgentOrSupportGateway() {
		assertThat(context.getEnvironment().getProperty("spring.application.name"))
				.isEqualTo("delivery-agent");
		assertThat(port).isPositive();
		assertThat(context.getBeansOfType(DeliveryAgent.class)).hasSize(1);
		assertThat(context.getBeansOfType(SupportAgentService.class)).isEmpty();
		assertThat(context.getBeansOfType(DeliveryAgentApiClient.class)).isEmpty();
	}
}
