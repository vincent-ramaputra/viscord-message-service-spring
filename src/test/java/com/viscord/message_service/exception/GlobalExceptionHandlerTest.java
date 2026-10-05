package com.viscord.message_service.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("gRPC status codes map to the matching HTTP status")
    @CsvSource({
            "INVALID_ARGUMENT, BAD_REQUEST",
            "PERMISSION_DENIED, FORBIDDEN",
            "NOT_FOUND, NOT_FOUND",
            "UNAVAILABLE, SERVICE_UNAVAILABLE",
            "DEADLINE_EXCEEDED, GATEWAY_TIMEOUT",
            "UNKNOWN, BAD_GATEWAY",
            "INTERNAL, BAD_GATEWAY",
            "UNAUTHENTICATED, BAD_GATEWAY"
    })
    void handleGrpcStatus_MapsCodeToHttpStatus(Status.Code code, HttpStatus expected) {
        ResponseEntity<ErrorResponse> response = handler.handleGrpcStatus(Status.fromCode(code).asRuntimeException());

        Assertions.assertEquals(expected, response.getStatusCode());
    }

    @Test
    @DisplayName("Client errors pass the downstream message through")
    void handleGrpcStatus_ClientError_PassesMessageThrough() {
        StatusRuntimeException e = Status.PERMISSION_DENIED.withDescription("User is not a member of this guild").asRuntimeException();

        ResponseEntity<ErrorResponse> response = handler.handleGrpcStatus(e);

        Assertions.assertEquals("User is not a member of this guild", response.getBody().message());
    }

    @Test
    @DisplayName("Server errors hide the downstream message")
    void handleGrpcStatus_ServerError_HidesDownstreamMessage() {
        StatusRuntimeException e = Status.UNKNOWN.withDescription("TypeError: cannot read properties of undefined").asRuntimeException();

        ResponseEntity<ErrorResponse> response = handler.handleGrpcStatus(e);

        Assertions.assertFalse(response.getBody().message().contains("TypeError"));
    }
}
