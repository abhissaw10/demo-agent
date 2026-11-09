package com.example.demo_agent.repository;

import com.example.demo_agent.domain.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, String> {

	List<CustomerOrder> findByCustomerIdOrderByOrderStartDateDesc(String customerId);
}
