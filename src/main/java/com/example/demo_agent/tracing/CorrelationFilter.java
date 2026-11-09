package com.example.demo_agent.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	public static final String SPAN_ID_HEADER = "X-Span-Id";
	public static final String SESSION_ID_HEADER = "X-Session-Id";
	public static final String TRACE_ID_ATTRIBUTE = CorrelationFilter.class.getName() + ".traceId";
	public static final String SPAN_ID_ATTRIBUTE = CorrelationFilter.class.getName() + ".spanId";
	public static final String SESSION_ID_ATTRIBUTE = CorrelationFilter.class.getName() + ".sessionId";

	private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String traceId = headerOrGenerate(request, TRACE_ID_HEADER, CorrelationIds::newTraceId);
		String spanId = headerOrGenerate(request, SPAN_ID_HEADER, CorrelationIds::newSpanId);
		String sessionId = headerOrGenerate(request, SESSION_ID_HEADER, () -> UUID.randomUUID().toString());

		request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
		request.setAttribute(SPAN_ID_ATTRIBUTE, spanId);
		request.setAttribute(SESSION_ID_ATTRIBUTE, sessionId);
		response.setHeader(TRACE_ID_HEADER, traceId);
		response.setHeader(SPAN_ID_HEADER, spanId);
		response.setHeader(SESSION_ID_HEADER, sessionId);

		try (MDC.MDCCloseable ignoredTrace = MDC.putCloseable("supportTraceId", traceId);
			 MDC.MDCCloseable ignoredSpan = MDC.putCloseable("supportSpanId", spanId);
			 MDC.MDCCloseable ignoredSession = MDC.putCloseable("sessionId", sessionId)) {
			log.info("HTTP request started method={} path={}", request.getMethod(), request.getRequestURI());
			filterChain.doFilter(request, response);
			log.info("HTTP request completed method={} path={} status={}",
					request.getMethod(), request.getRequestURI(), response.getStatus());
		}
	}

	private static String headerOrGenerate(HttpServletRequest request, String header, Supplier<String> generator) {
		String value = request.getHeader(header);
		if (value == null) {
			return generator.get();
		}
		String trimmed = value.trim();
		return SAFE_ID.matcher(trimmed).matches() ? trimmed : generator.get();
	}

}
