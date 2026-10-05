package com.viscord.message_service.config;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class GrpcClientConfigTest {

    private final ClientInterceptor interceptor = new GrpcClientConfig().deadlineInterceptor(Duration.ofSeconds(2));

    private CallOptions intercept(CallOptions callOptions) {
        AtomicReference<CallOptions> captured = new AtomicReference<>();
        Channel channel = new Channel() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method, CallOptions options) {
                captured.set(options);
                return null;
            }

            @Override
            public String authority() {
                return "test";
            }
        };
        interceptor.interceptCall(null, callOptions, channel);
        return captured.get();
    }

    @Test
    @DisplayName("Calls without a deadline get the default deadline")
    void interceptCall_NoDeadline_AddsDefault() {
        Deadline deadline = intercept(CallOptions.DEFAULT).getDeadline();

        Assertions.assertNotNull(deadline);
        long remainingMs = deadline.timeRemaining(TimeUnit.MILLISECONDS);
        Assertions.assertTrue(remainingMs > 0 && remainingMs <= 2000, "remaining: " + remainingMs);
    }

    @Test
    @DisplayName("Calls that set their own deadline keep it")
    void interceptCall_ExistingDeadline_KeepsIt() {
        CallOptions withDeadline = CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS);

        Deadline deadline = intercept(withDeadline).getDeadline();

        Assertions.assertSame(withDeadline.getDeadline(), deadline);
    }
}
