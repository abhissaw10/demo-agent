package com.example.demo_agent.controller;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.service.CustomerOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customers")
public class OrderController {

	private final CustomerOrderService customerOrderService;

	public OrderController(CustomerOrderService customerOrderService) {
		this.customerOrderService = customerOrderService;
	}

	@GetMapping("/{customerId}/orders")
	public ResponseEntity<List<CustomerOrderResponse>> getCustomerOrders(
			@PathVariable String customerId) {
		return ResponseEntity.ok(customerOrderService.getCustomerOrders(customerId));
	}
}
