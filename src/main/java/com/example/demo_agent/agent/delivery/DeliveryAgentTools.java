package com.example.demo_agent.agent.delivery;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.client.OrderApiClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Delivery-only resource tools. They are not exposed to the support model. */
@Component
class DeliveryAgentTools {

	private final OrderApiClient orderApiClient;

	DeliveryAgentTools(OrderApiClient orderApiClient) {
		this.orderApiClient = orderApiClient;
	}

	List<CustomerOrderResponse> getCustomerOrders(String customerId) {
		return orderApiClient.getCustomerOrders(customerId);
	}

	OrderDeliveryStatusResponse getDeliveryStatus(String orderId) {
		return orderApiClient.getDeliveryStatus(orderId);
	}
}
