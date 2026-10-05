package com.viscord.message_service.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends ResponseEntityExceptionHandler so Spring MVC's own exceptions (unsupported media type,
 * malformed JSON, missing header/part, type mismatch, 405, 404, ...) get their proper 4xx status
 * instead of falling through to the generic 500 handler below.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
        log.info("Request rejected with 400: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenRequest(ForbiddenException e) {
        log.info("Request rejected with 403: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getMessage()));
    }

    // Handled by the base class; declaring @ExceptionHandler(MethodArgumentNotValidException.class)
    // here as well would fail at startup with an ambiguous handler mapping.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");

        return handleExceptionInternal(e, new ErrorResponse(message), headers, status, request);
    }

    /**
     * Every base-class handler ends here. The base class builds an RFC 7807 ProblemDetail body;
     * convert it to our ErrorResponse so clients see one error shape across the API.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response == null || response.getBody() instanceof ErrorResponse) {
            return response;
        }

        String message = "Request failed";
        if (response.getBody() instanceof ProblemDetail problem) {
            message = problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
        }

        if (status.is5xxServerError()) {
            log.error("Request failed: {}", e.getMessage(), e);
        } else {
            log.info("Request rejected with {}: {}", status.value(), e.getMessage());
        }

        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(new ErrorResponse(message));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        log.info("Request rejected with 404: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * Maps failures of outbound gRPC calls (e.g. guild-service) to HTTP statuses.
     * Client-caused codes pass the downstream message through; for server-side failures the
     * downstream message is only logged, so internal details don't leak to the client.
     */
    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<ErrorResponse> handleGrpcStatus(StatusRuntimeException e) {
        Status status = e.getStatus();
        HttpStatus httpStatus = switch (status.getCode()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };

        if (httpStatus.is4xxClientError()) {
            log.info("Downstream gRPC call rejected: {} {}", status.getCode(), status.getDescription());
            String message = status.getDescription() != null ? status.getDescription() : httpStatus.getReasonPhrase();
            return ResponseEntity.status(httpStatus).body(new ErrorResponse(message));
        }

        log.error("Downstream gRPC call failed: {}", status, e);
        String message = switch (httpStatus) {
            case SERVICE_UNAVAILABLE -> "A dependent service is unavailable, please try again";
            case GATEWAY_TIMEOUT -> "A dependent service timed out, please try again";
            default -> "A dependent service returned an error";
        };
        return ResponseEntity.status(httpStatus).body(new ErrorResponse(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Unknown error"));
    }
}
