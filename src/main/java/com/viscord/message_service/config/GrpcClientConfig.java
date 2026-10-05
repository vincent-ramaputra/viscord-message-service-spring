package com.viscord.message_service.config;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class GrpcClientConfig {

    /**
     * gRPC calls have no deadline by default, so a hung downstream service would block the
     * request thread forever. The deadline is computed per call; a stub created once with
     * withDeadlineAfter() would carry an absolute deadline that expires shortly after startup.
     */
    @GrpcGlobalClientInterceptor
    ClientInterceptor deadlineInterceptor(@Value("${viscord.grpc.client-deadline:2s}") Duration deadline) {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                if (callOptions.getDeadline() == null) {
                    callOptions = callOptions.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
                }
                return next.newCall(method, callOptions);
            }
        };
    }
}
