package com.example.demo_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(basePackages = "com.example.demo_agent", excludeFilters = {
		@ComponentScan.Filter(type = FilterType.REGEX,
				pattern = "com\\.example\\.demo_agent\\.agent\\.delivery\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX,
				pattern = "com\\.example\\.demo_agent\\.deliveryapp\\..*")
})
public class DemoAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoAgentApplication.class, args);
	}

}
