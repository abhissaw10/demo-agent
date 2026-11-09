package com.example.demo_agent.ui;

import com.example.demo_agent.api.ChatRequest;
import com.example.demo_agent.api.ChatResponse;
import com.example.demo_agent.client.SupportChatApiClient;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.UUID;

@Route("")
@PageTitle("Order Support Chat")
public class SupportChatView extends VerticalLayout {

	private final SupportChatApiClient chatApiClient;
	private final String sessionId = UUID.randomUUID().toString();
	private final Div messages = new Div();
	private final TextField customerId = new TextField("Customer ID");
	private final TextField input = new TextField();
	private final Button send = new Button("Send");

	public SupportChatView(SupportChatApiClient chatApiClient) {
		this.chatApiClient = chatApiClient;
		setSizeFull();
		setPadding(false);
		setSpacing(false);
		getStyle().set("background", "linear-gradient(145deg, #f5f7fb 0%, #eef2ff 100%)");

		VerticalLayout shell = new VerticalLayout();
		shell.setWidth("min(860px, calc(100% - 32px))");
		shell.setHeight("calc(100vh - 40px)");
		shell.getStyle()
				.set("margin", "20px auto")
				.set("background", "white")
				.set("border-radius", "18px")
				.set("box-shadow", "0 18px 55px rgba(39, 49, 82, 0.12)")
				.set("overflow", "hidden");

		H1 title = new H1("Order support");
		title.getStyle().set("margin", "0").set("font-size", "1.5rem");
		Paragraph subtitle = new Paragraph("Grounded answers from verified order, delivery, and policy data");
		subtitle.getStyle().set("margin", "4px 0 0").set("color", "#667085");
		Div heading = new Div(title, subtitle);
		heading.setWidthFull();
		heading.getStyle().set("padding", "20px 24px").set("border-bottom", "1px solid #eaecf0");

		messages.setSizeFull();
		messages.getStyle().set("overflow-y", "auto").set("padding", "20px");
		addBubble("assistant", "Hi — enter your customer ID and ask about an order, delivery, refund, or exchange.");

		customerId.setPlaceholder("CUST-1001");
		customerId.setWidth("180px");
		input.setPlaceholder("Type your message…");
		input.setClearButtonVisible(true);
		input.setWidthFull();
		input.addKeyPressListener(Key.ENTER, event -> sendMessage());
		send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
		send.addClickListener(event -> sendMessage());

		HorizontalLayout composer = new HorizontalLayout(customerId, input, send);
		composer.setWidthFull();
		composer.setAlignItems(Alignment.END);
		composer.setPadding(true);
		composer.getStyle().set("border-top", "1px solid #eaecf0");

		shell.add(heading, messages, composer);
		shell.setFlexGrow(1, messages);
		add(shell);
	}

	private void sendMessage() {
		String customer = customerId.getValue().trim();
		String text = input.getValue().trim();
		if (customer.isEmpty() || text.isEmpty()) {
			addBubble("assistant", "Please enter both a customer ID and a message.");
			return;
		}
		addBubble("user", text);
		input.clear();
		send.setEnabled(false);
		input.setEnabled(false);
		try {
			ChatResponse response = chatApiClient.chat(new ChatRequest(text, customer), sessionId);
			String answer = response == null ? "No response was returned." : response.message();
			addBubble("assistant", answer);
		} catch (RuntimeException exception) {
			addBubble("assistant", "The chat service is temporarily unavailable. Please try again.");
		} finally {
			send.setEnabled(true);
			input.setEnabled(true);
			input.focus();
		}
	}

	private void addBubble(String role, String text) {
		Div row = new Div();
		row.setWidthFull();
		row.getStyle().set("display", "flex")
				.set("justify-content", "user".equals(role) ? "flex-end" : "flex-start")
				.set("margin-bottom", "12px");
		Div bubble = new Div();
		bubble.setText(text);
		bubble.getStyle()
				.set("max-width", "76%")
				.set("white-space", "pre-wrap")
				.set("padding", "12px 15px")
				.set("border-radius", "16px")
				.set("line-height", "1.45")
				.set("background", "user".equals(role) ? "#4f46e5" : "#f2f4f7")
				.set("color", "user".equals(role) ? "white" : "#1d2939");
		row.add(bubble);
		messages.add(row);
		messages.getElement().executeJs("this.scrollTop = this.scrollHeight");
	}
}
