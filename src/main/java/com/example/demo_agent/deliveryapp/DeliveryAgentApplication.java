package com.example.demo_agent.deliveryapp;

import com.example.demo_agent.client.OrderApiClient;
import com.example.demo_agent.config.AgentInfrastructureConfiguration;
import com.example.demo_agent.config.DeliveryAgentProperties;
import com.example.demo_agent.config.SupportAgentProperties;
import com.example.demo_agent.exception.GlobalExceptionHandler;
import com.vaadin.flow.spring.SpringBootAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = {
		"com.example.demo_agent.agent.delivery",
		"com.example.demo_agent.deliveryapp",
		"com.example.demo_agent.tracing"
}, exclude = {
		DataSourceAutoConfiguration.class,
		DataSourceTransactionManagerAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		DataSourceInitializationAutoConfiguration.class,
		SpringBootAutoConfiguration.class
})
@EnableConfigurationProperties({SupportAgentProperties.class, DeliveryAgentProperties.class})
@Import({AgentInfrastructureConfiguration.class, OrderApiClient.class, GlobalExceptionHandler.class})
public class DeliveryAgentApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(DeliveryAgentApplication.class);
		application.setAdditionalProfiles("delivery");
		application.run(args);
	}
}
