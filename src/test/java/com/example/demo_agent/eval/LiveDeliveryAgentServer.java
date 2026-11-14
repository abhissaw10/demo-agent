package com.example.demo_agent.eval;

import com.example.demo_agent.deliveryapp.DeliveryAgentApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the separately configured Delivery application for end-to-end live evaluations. */
final class LiveDeliveryAgentServer implements AutoCloseable {

	private final ConfigurableApplicationContext context;

	private LiveDeliveryAgentServer(ConfigurableApplicationContext context) {
		this.context = context;
	}

	static LiveDeliveryAgentServer start() {
		int supportPort = port("LIVE_EVAL_PORT", 18080);
		int deliveryPort = port("LIVE_DELIVERY_EVAL_PORT", 18081);
		ConfigurableApplicationContext context = new SpringApplicationBuilder(DeliveryAgentApplication.class)
				.profiles("delivery")
				.run("--server.port=" + deliveryPort,
						"--support.order-api.base-url=http://localhost:" + supportPort,
						"--support.llm.max-retries=0");
		return new LiveDeliveryAgentServer(context);
	}

	@Override
	public void close() {
		context.close();
	}

	private static int port(String name, int fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
	}
}
