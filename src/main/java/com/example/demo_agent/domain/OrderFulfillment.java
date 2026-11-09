package com.example.demo_agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_fulfillment")
public class OrderFulfillment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "fulfillment_id")
	private Long fulfillmentId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private CustomerOrder order;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "status_id", nullable = false)
	private OrderFulfillmentStatus status;

	@Column(name = "status_update_timestamp", nullable = false)
	private LocalDateTime statusUpdateTimestamp;

	@Column(name = "details")
	private String details;

	protected OrderFulfillment() {
	}

	public OrderFulfillment(Long fulfillmentId, CustomerOrder order, OrderFulfillmentStatus status,
			LocalDateTime statusUpdateTimestamp, String details) {
		this.fulfillmentId = fulfillmentId;
		this.order = order;
		this.status = status;
		this.statusUpdateTimestamp = statusUpdateTimestamp;
		this.details = details;
	}

	public Long getFulfillmentId() {
		return fulfillmentId;
	}

	public CustomerOrder getOrder() {
		return order;
	}

	public OrderFulfillmentStatus getStatus() {
		return status;
	}

	public LocalDateTime getStatusUpdateTimestamp() {
		return statusUpdateTimestamp;
	}

	public String getDetails() {
		return details;
	}
}
