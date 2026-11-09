package com.example.demo_agent.api;

import java.util.List;

public record OrderDeliveryStatusResponse(
		String orderId,
		DeliveryUpdateResponse currentStatus,
		List<DeliveryUpdateResponse> updates) {
}
