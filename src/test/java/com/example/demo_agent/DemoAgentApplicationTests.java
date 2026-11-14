package com.example.demo_agent;

import com.example.demo_agent.agent.delivery.DeliveryAgent;
import com.example.demo_agent.agent.support.SupportAgentService;
import com.example.demo_agent.client.DeliveryAgentApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.startsWith;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:sqlite::memory:",
		"spring.datasource.hikari.maximum-pool-size=1"
})
@AutoConfigureMockMvc
class DemoAgentApplicationTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
		assertThat(context.getBeansOfType(SupportAgentService.class)).hasSize(1);
		assertThat(context.getBeansOfType(DeliveryAgentApiClient.class)).hasSize(1);
		assertThat(context.getBeansOfType(DeliveryAgent.class)).isEmpty();
	}

	@Test
	void pingReturnsHello() throws Exception {
		mockMvc.perform(get("/ping"))
				.andExpect(status().isOk())
				.andExpect(content().string("hello"));
	}

	@Test
	void seededCustomerOrdersAreAvailable() throws Exception {
		mockMvc.perform(get("/customers/CUST-1001/orders"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$[0].customerId").value("CUST-1001"));
	}

	@Test
	void seededDeliveryHistoryIsAvailable() throws Exception {
		mockMvc.perform(get("/orders/ORD-1001/delivery-status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currentStatus.statusCode").value("DELIVERED"))
				.andExpect(jsonPath("$.updates.length()").value(8));
	}

	@Test
	void packagedRefundPolicyIsAvailable() throws Exception {
		mockMvc.perform(get("/policies/refund"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("text/markdown;charset=UTF-8"))
				.andExpect(content().string(startsWith("# Refund Policy")));
	}

	@Test
	void chatFailsSafelyWhenModelKeyIsNotConfigured() throws Exception {
		mockMvc.perform(post("/chat")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"message\":\"Where is ORD-1001?\",\"customerId\":\"CUST-1001\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("SAFE_FAILURE"))
				.andExpect(jsonPath("$.llmCalls").value(0))
				.andExpect(jsonPath("$.toolCalls").value(0));
	}

}
