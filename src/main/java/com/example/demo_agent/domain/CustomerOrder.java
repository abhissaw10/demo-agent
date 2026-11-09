package com.example.demo_agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_order")
public class CustomerOrder {

	@Id
	@Column(name = "order_id", nullable = false, updatable = false)
	private String orderId;

	@Column(name = "customer_id", nullable = false)
	private String customerId;

	@Column(name = "order_start_date", nullable = false)
	private LocalDateTime orderStartDate;

	protected CustomerOrder() {
	}

	public CustomerOrder(String orderId, String customerId, LocalDateTime orderStartDate) {
		this.orderId = orderId;
		this.customerId = customerId;
		this.orderStartDate = orderStartDate;
	}

	public String getOrderId() {
		return orderId;
	}

	public String getCustomerId() {
		return customerId;
	}

	public LocalDateTime getOrderStartDate() {
		return orderStartDate;
	}
}
