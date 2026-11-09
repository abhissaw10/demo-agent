package com.example.demo_agent.service;

import com.example.demo_agent.api.CustomerOrderResponse;
import com.example.demo_agent.domain.CustomerOrder;
import com.example.demo_agent.domain.OrderFulfillment;
import com.example.demo_agent.domain.OrderFulfillmentStatus;
import com.example.demo_agent.exception.ResourceNotFoundException;
import com.example.demo_agent.repository.CustomerOrderRepository;
import com.example.demo_agent.repository.OrderFulfillmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerOrderServiceTest {

	@Mock
	private CustomerOrderRepository customerOrderRepository;

	@Mock
	private OrderFulfillmentRepository orderFulfillmentRepository;

	@InjectMocks
	private CustomerOrderService service;

	@Test
	void getCustomerOrdersReturnsOrdersWithLatestOverallStatus() {
		LocalDateTime orderTime = LocalDateTime.of(2026, 10, 25, 9, 30);
		LocalDateTime statusTime = LocalDateTime.of(2026, 10, 28, 14, 32);
		CustomerOrder order = new CustomerOrder("ORD-1001", "CUST-1001", orderTime);
		OrderFulfillmentStatus status = new OrderFulfillmentStatus(
				8L, "DELIVERED", "Delivered", "The shipment was delivered.", true);
		OrderFulfillment fulfillment = new OrderFulfillment(1L, order, status, statusTime, "Delivered.");

		when(customerOrderRepository.findByCustomerIdOrderByOrderStartDateDesc("CUST-1001"))
				.thenReturn(List.of(order));
		when(orderFulfillmentRepository.findFirstByOrderOrderIdOrderByStatusUpdateTimestampDesc("ORD-1001"))
				.thenReturn(Optional.of(fulfillment));

		List<CustomerOrderResponse> response = service.getCustomerOrders("CUST-1001");

		assertThat(response).hasSize(1);
		assertThat(response.getFirst().orderId()).isEqualTo("ORD-1001");
		assertThat(response.getFirst().overallStatus().code()).isEqualTo("DELIVERED");
		assertThat(response.getFirst().overallStatus().terminal()).isTrue();
	}

	@Test
	void getCustomerOrdersReturnsNotFoundWhenCustomerHasNoOrders() {
		when(customerOrderRepository.findByCustomerIdOrderByOrderStartDateDesc("CUST-404"))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.getCustomerOrders("CUST-404"))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("CUST-404");
	}
}
