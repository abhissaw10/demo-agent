package com.example.demo_agent.controller;

import com.example.demo_agent.api.DeliveryUpdateResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.exception.GlobalExceptionHandler;
import com.example.demo_agent.exception.ResourceNotFoundException;
import com.example.demo_agent.service.DeliveryStatusService;
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

class DeliveryControllerTest {

	private DeliveryStatusService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(DeliveryStatusService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new DeliveryController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.addFilter(new CorrelationFilter())
				.build();
	}

	@Test
	void getOrderDeliveryStatusReturnsHistoryAndGeneratedCorrelationHeaders() throws Exception {
		DeliveryUpdateResponse update = new DeliveryUpdateResponse(
				"OUT_FOR_DELIVERY", "Out for delivery", "A delivery agent has the shipment.",
				"Assigned to local route.", LocalDateTime.of(2026, 10, 28, 9, 5), true, false);
		when(service.getOrderDeliveryStatus("ORD-1001"))
				.thenReturn(new OrderDeliveryStatusResponse("ORD-1001", update, List.of(update)));

		mockMvc.perform(get("/orders/ORD-1001/delivery-status"))
				.andExpect(status().isOk())
				.andExpect(header().exists(CorrelationFilter.TRACE_ID_HEADER))
				.andExpect(header().exists(CorrelationFilter.SPAN_ID_HEADER))
				.andExpect(header().exists(CorrelationFilter.SESSION_ID_HEADER))
				.andExpect(jsonPath("$.currentStatus.statusCode").value("OUT_FOR_DELIVERY"))
				.andExpect(jsonPath("$.updates[0].current").value(true));
	}

	@Test
	void getOrderDeliveryStatusReturnsNotFoundForUnknownOrder() throws Exception {
		when(service.getOrderDeliveryStatus("ORD-404")).thenThrow(
				new ResourceNotFoundException("ORDER_NOT_FOUND", "Order ORD-404 was not found."));

		mockMvc.perform(get("/orders/ORD-404/delivery-status"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
	}
}
