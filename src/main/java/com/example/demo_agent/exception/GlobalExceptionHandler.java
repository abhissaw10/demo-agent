package com.example.demo_agent.exception;

import com.example.demo_agent.tracing.CorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception, HttpServletRequest request) {
		if (exception.getStatus().is5xxServerError()) {
			log.error("Request failed with code {}", exception.getCode(), exception);
		} else {
			log.warn("Request rejected with code {}: {}", exception.getCode(), exception.getMessage());
		}
		return response(exception.getStatus(), exception.getCode(), exception.getMessage(), request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception,
			HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND",
				"The requested endpoint does not exist.", request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
			HttpServletRequest request) {
		return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
				"The HTTP method is not supported for this endpoint.", request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		String message = exception.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getField() + " " + error.getDefaultMessage())
				.orElse("The request body is invalid.");
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception, HttpServletRequest request) {
		log.error("Unexpected request failure", exception);
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"An unexpected error occurred.", request);
	}

	private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
			HttpServletRequest request) {
		Object traceId = request.getAttribute(CorrelationFilter.TRACE_ID_ATTRIBUTE);
		ApiErrorResponse body = new ApiErrorResponse(
				Instant.now(),
				status.value(),
				status.getReasonPhrase(),
				code,
				message,
				request.getRequestURI(),
				traceId == null ? null : traceId.toString());
		return ResponseEntity.status(status).body(body);
	}
}
