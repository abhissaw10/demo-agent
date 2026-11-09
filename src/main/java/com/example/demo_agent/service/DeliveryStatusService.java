package com.example.demo_agent.service;

import com.example.demo_agent.api.DeliveryUpdateResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import com.example.demo_agent.domain.OrderFulfillment;
import com.example.demo_agent.domain.OrderFulfillmentStatus;
import com.example.demo_agent.exception.ResourceNotFoundException;
import com.example.demo_agent.repository.CustomerOrderRepository;
import com.example.demo_agent.repository.OrderFulfillmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class DeliveryStatusService {

	private static final Logger log = LoggerFactory.getLogger(DeliveryStatusService.class);

	private final CustomerOrderRepository customerOrderRepository;
	private final OrderFulfillmentRepository orderFulfillmentRepository;

	public DeliveryStatusService(CustomerOrderRepository customerOrderRepository,
			OrderFulfillmentRepository orderFulfillmentRepository) {
		this.customerOrderRepository = customerOrderRepository;
		this.orderFulfillmentRepository = orderFulfillmentRepository;
	}

	@Transactional(readOnly = true)
	public OrderDeliveryStatusResponse getOrderDeliveryStatus(String orderId) {
		String validatedOrderId = IdentifierValidator.require(orderId, "orderId");
		if (!customerOrderRepository.existsById(validatedOrderId)) {
			throw new ResourceNotFoundException("ORDER_NOT_FOUND",
					"Order " + validatedOrderId + " was not found.");
		}

		List<OrderFulfillment> fulfillmentUpdates = orderFulfillmentRepository
				.findByOrderOrderIdOrderByStatusUpdateTimestampAsc(validatedOrderId);
		if (fulfillmentUpdates.isEmpty()) {
			throw new ResourceNotFoundException("ORDER_STATUS_NOT_FOUND",
					"No delivery updates were found for order " + validatedOrderId + ".");
		}

		List<DeliveryUpdateResponse> updates = new ArrayList<>(fulfillmentUpdates.size());
		for (int index = 0; index < fulfillmentUpdates.size(); index++) {
			updates.add(toResponse(fulfillmentUpdates.get(index), index == fulfillmentUpdates.size() - 1));
		}
		List<DeliveryUpdateResponse> immutableUpdates = List.copyOf(updates);
		log.info("Retrieved {} delivery updates for orderId={}", immutableUpdates.size(), validatedOrderId);
		return new OrderDeliveryStatusResponse(
				validatedOrderId, immutableUpdates.get(immutableUpdates.size() - 1), immutableUpdates);
	}

	private DeliveryUpdateResponse toResponse(OrderFulfillment update, boolean current) {
		OrderFulfillmentStatus status = update.getStatus();
		return new DeliveryUpdateResponse(
				status.getStatusCode(),
				status.getDisplayName(),
				status.getDescription(),
				update.getDetails(),
				update.getStatusUpdateTimestamp(),
				current,
				status.isTerminalStatus());
	}
}
