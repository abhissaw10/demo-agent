package com.example.demo_agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_fulfillment_status")
public class OrderFulfillmentStatus {

	@Id
	@Column(name = "status_id", nullable = false, updatable = false)
	private Long statusId;

	@Column(name = "status_code", nullable = false, unique = true)
	private String statusCode;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "description", nullable = false)
	private String description;

	@Column(name = "terminal_status", nullable = false)
	private boolean terminalStatus;

	protected OrderFulfillmentStatus() {
	}

	public OrderFulfillmentStatus(Long statusId, String statusCode, String displayName,
			String description, boolean terminalStatus) {
		this.statusId = statusId;
		this.statusCode = statusCode;
		this.displayName = displayName;
		this.description = description;
		this.terminalStatus = terminalStatus;
	}

	public Long getStatusId() {
		return statusId;
	}

	public String getStatusCode() {
		return statusCode;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public boolean isTerminalStatus() {
		return terminalStatus;
	}
}
