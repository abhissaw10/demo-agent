package com.example.demo_agent.agent;

import com.example.demo_agent.api.DeliveryUpdateResponse;
import com.example.demo_agent.api.OrderDeliveryStatusResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryInvestigatorTest {

	private final DeliveryInvestigator investigator = new DeliveryInvestigator();

	@Test
	void lostShipmentProducesGroundedHumanApprovalRecommendation() {
		DeliveryUpdateResponse update = new DeliveryUpdateResponse("LOST_IN_TRANSIT", "Lost in transit",
				"Carrier declared it lost.", "Investigation complete.", LocalDateTime.of(2026, 10, 21, 12, 0),
				true, true);

		DeliveryInvestigator.DeliveryFinding finding = investigator.investigate(
				new OrderDeliveryStatusResponse("ORD-1005", update, List.of(update)));

		assertThat(finding.exception()).isTrue();
		assertThat(finding.finding()).contains("declared").contains("lost");
		assertThat(finding.recommendedNextStep()).contains("human").contains("approve");
	}
}
