package com.example.demo_agent.service;

import com.example.demo_agent.api.OrderDeliveryStatusResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryStatusServiceTest {

	@Mock
	private CustomerOrderRepository customerOrderRepository;

	@Mock
	private OrderFulfillmentRepository orderFulfillmentRepository;

	@InjectMocks
	private DeliveryStatusService service;

	@Test
	void getOrderDeliveryStatusReturnsChronologicalUpdatesAndCurrentStatus() {
		CustomerOrder order = new CustomerOrder("ORD-1001", "CUST-1001",
				LocalDateTime.of(2026, 10, 25, 9, 30));
		OrderFulfillmentStatus placed = new OrderFulfillmentStatus(
				1L, "ORDER_PLACED", "Order placed", "The order was accepted.", false);
		OrderFulfillmentStatus shipped = new OrderFulfillmentStatus(
				3L, "SHIPPED_BY_MERCHANT", "Shipped by merchant", "The order was shipped.", false);
		List<OrderFulfillment> updates = List.of(
				new OrderFulfillment(1L, order, placed, LocalDateTime.of(2026, 10, 25, 9, 30), "Accepted."),
				new OrderFulfillment(2L, order, shipped, LocalDateTime.of(2026, 10, 26, 10, 10), "Picked up."));

		when(customerOrderRepository.existsById("ORD-1001")).thenReturn(true);
		when(orderFulfillmentRepository.findByOrderOrderIdOrderByStatusUpdateTimestampAsc("ORD-1001"))
				.thenReturn(updates);

		OrderDeliveryStatusResponse response = service.getOrderDeliveryStatus("ORD-1001");

		assertThat(response.updates()).hasSize(2);
		assertThat(response.updates().getFirst().current()).isFalse();
		assertThat(response.currentStatus().statusCode()).isEqualTo("SHIPPED_BY_MERCHANT");
		assertThat(response.currentStatus().current()).isTrue();
	}

	@Test
	void getOrderDeliveryStatusReturnsNotFoundForUnknownOrder() {
		when(customerOrderRepository.existsById("ORD-404")).thenReturn(false);

		assertThatThrownBy(() -> service.getOrderDeliveryStatus("ORD-404"))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("ORD-404");
	}
}
