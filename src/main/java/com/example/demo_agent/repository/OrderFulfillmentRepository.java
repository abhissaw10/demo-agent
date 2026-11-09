package com.example.demo_agent.repository;

import com.example.demo_agent.domain.OrderFulfillment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderFulfillmentRepository extends JpaRepository<OrderFulfillment, Long> {

	List<OrderFulfillment> findByOrderOrderIdOrderByStatusUpdateTimestampAsc(String orderId);

	Optional<OrderFulfillment> findFirstByOrderOrderIdOrderByStatusUpdateTimestampDesc(String orderId);
}
