package com.example.demo_agent.service;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.api.OrderStatusResponse;
import com.example.demo_agent.domain.CustomerOrder;
import com.example.demo_agent.domain.OrderFulfillment;
import com.example.demo_agent.domain.OrderFulfillmentStatus;
import com.example.demo_agent.exception.ResourceNotFoundException;
import com.example.demo_agent.repository.CustomerOrderRepository;
import com.example.demo_agent.repository.OrderFulfillmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CustomerOrderService {

	private static final Logger log = LoggerFactory.getLogger(CustomerOrderService.class);

	private final CustomerOrderRepository customerOrderRepository;
	private final OrderFulfillmentRepository orderFulfillmentRepository;

	public CustomerOrderService(CustomerOrderRepository customerOrderRepository,
			OrderFulfillmentRepository orderFulfillmentRepository) {
		this.customerOrderRepository = customerOrderRepository;
		this.orderFulfillmentRepository = orderFulfillmentRepository;
	}

	@Transactional(readOnly = true)
	public List<CustomerOrderResponse> getCustomerOrders(String customerId) {
		String validatedCustomerId = IdentifierValidator.require(customerId, "customerId");
		List<CustomerOrder> orders = customerOrderRepository
				.findByCustomerIdOrderByOrderStartDateDesc(validatedCustomerId);
		if (orders.isEmpty()) {
			throw new ResourceNotFoundException("CUSTOMER_ORDERS_NOT_FOUND",
					"No orders were found for customer " + validatedCustomerId + ".");
		}

		List<CustomerOrderResponse> response = orders.stream().map(this::toResponse).toList();
		log.info("Retrieved {} orders for customerId={}", response.size(), validatedCustomerId);
		return response;
	}

	private CustomerOrderResponse toResponse(CustomerOrder order) {
		OrderFulfillment latest = orderFulfillmentRepository
				.findFirstByOrderOrderIdOrderByStatusUpdateTimestampDesc(order.getOrderId())
				.orElseThrow(() -> new ResourceNotFoundException("ORDER_STATUS_NOT_FOUND",
						"No fulfillment status was found for order " + order.getOrderId() + "."));
		OrderFulfillmentStatus status = latest.getStatus();
		OrderStatusResponse overallStatus = new OrderStatusResponse(
				status.getStatusCode(),
				status.getDisplayName(),
				status.getDescription(),
				latest.getStatusUpdateTimestamp(),
				status.isTerminalStatus());
		return new CustomerOrderResponse(
				order.getCustomerId(), order.getOrderId(), order.getOrderStartDate(), overallStatus);
	}
}
