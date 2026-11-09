package com.example.demo_agent.agent;

import com.example.demo_agent.api.DeliveryUpdateResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DeliveryInvestigator {

	public DeliveryFinding investigate(OrderDeliveryStatusResponse status) {
		DeliveryUpdateResponse current = status.currentStatus();
		return switch (current.statusCode()) {
			case "DELIVERED" -> finding(current, "The carrier record confirms delivery.",
					"If the customer disputes receipt, route the case for human proof-of-delivery review.", false);
			case "LOST_IN_TRANSIT" -> finding(current, "The carrier has declared the shipment lost in transit.",
					"A human support agent must validate and approve any refund or replacement.", true);
			case "DELIVERY_AGENT_UNREACHABLE" -> finding(current,
					"Carrier operations cannot currently reach the assigned delivery agent.",
					"Escalate tracking follow-up; do not promise a delivery date or issue compensation automatically.", true);
			case "AWAITING_INVENTORY_REFRESH" -> finding(current,
					"Fulfillment is blocked while the merchant reconciles inventory.",
					"Check the refund policy and order age before recommending a cancellation request.", true);
			case "OUT_FOR_DELIVERY" -> finding(current, "The shipment is on the local delivery route.",
					"Advise the customer to remain available; do not invent an arrival time.", false);
			case "CANCELLED" -> finding(current, "The order is recorded as cancelled.",
					"A human agent can verify payment reversal status if the customer asks about funds.", false);
			default -> finding(current, "The shipment is still moving through fulfillment.",
					"Report the verified checkpoint only; do not infer an estimated delivery date.", false);
		};
	}

	private DeliveryFinding finding(DeliveryUpdateResponse current, String finding, String nextStep,
			boolean exception) {
		return new DeliveryFinding(current.statusCode(), current.status(), finding, current.details(),
				current.updatedAt(), current.terminal(), exception, nextStep);
	}

	public record DeliveryFinding(String statusCode, String status, String finding, String verifiedDetail,
			LocalDateTime observedAt, boolean terminal, boolean exception, String recommendedNextStep) {
	}
}
