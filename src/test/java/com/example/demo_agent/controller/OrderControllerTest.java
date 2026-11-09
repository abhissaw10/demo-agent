package com.example.demo_agent.controller;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.exception.GlobalExceptionHandler;
import com.example.demo_agent.exception.ResourceNotFoundException;
import com.example.demo_agent.service.CustomerOrderService;
import com.example.demo_agent.tracing.CorrelationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

	private CustomerOrderService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(CustomerOrderService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilter(new CorrelationFilter())
				.build();
	}

	@Test
	void getCustomerOrdersReturnsOrdersAndPropagatesCorrelationHeaders() throws Exception {
		LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 28, 14, 32);
		OrderStatusResponse statusResponse = new OrderStatusResponse(
				"DELIVERED", "Delivered", "The shipment was delivered.", updatedAt, true);
		CustomerOrderResponse orderResponse = new CustomerOrderResponse(
				"CUST-1001", "ORD-1001", LocalDateTime.of(2026, 10, 25, 9, 30), statusResponse);
		when(service.getCustomerOrders("CUST-1001")).thenReturn(List.of(orderResponse));

		mockMvc.perform(get("/customers/CUST-1001/orders")
					.header(CorrelationFilter.TRACE_ID_HEADER, "trace-123")
					.header(CorrelationFilter.SPAN_ID_HEADER, "span-123")
					.header(CorrelationFilter.SESSION_ID_HEADER, "session-123"))
				.andExpect(status().isOk())
				.andExpect(header().string(CorrelationFilter.TRACE_ID_HEADER, "trace-123"))
				.andExpect(header().string(CorrelationFilter.SPAN_ID_HEADER, "span-123"))
				.andExpect(header().string(CorrelationFilter.SESSION_ID_HEADER, "session-123"))
				.andExpect(jsonPath("$[0].orderId").value("ORD-1001"))
				.andExpect(jsonPath("$[0].overallStatus.code").value("DELIVERED"));
	}

	@Test
	void getCustomerOrdersReturnsRestErrorForUnknownCustomer() throws Exception {
		when(service.getCustomerOrders("CUST-404")).thenThrow(new ResourceNotFoundException(
				"CUSTOMER_ORDERS_NOT_FOUND", "No orders were found for customer CUST-404."));

		mockMvc.perform(get("/customers/CUST-404/orders"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_ORDERS_NOT_FOUND"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}
}
