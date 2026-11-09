package com.example.demo_agent.controller;

import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.service.DeliveryStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class DeliveryController {

	private final DeliveryStatusService deliveryStatusService;

	public DeliveryController(DeliveryStatusService deliveryStatusService) {
		this.deliveryStatusService = deliveryStatusService;
	}

	@GetMapping("/{orderId}/delivery-status")
	public ResponseEntity<OrderDeliveryStatusResponse> getOrderDeliveryStatus(
			@PathVariable String orderId) {
		return ResponseEntity.ok(deliveryStatusService.getOrderDeliveryStatus(orderId));
	}
}
