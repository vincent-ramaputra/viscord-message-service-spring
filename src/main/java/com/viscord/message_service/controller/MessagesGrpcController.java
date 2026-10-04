package com.viscord.message_service.controller;

import com.viscord.message_service.exception.BadRequestException;
import com.viscord.message_service.exception.NotFoundException;
import com.viscord.message_service.grpc.GetUnreadCountRequest;
import com.viscord.message_service.grpc.GetUnreadCountResponse;
import com.viscord.message_service.grpc.MessagesServiceGrpc;
import com.viscord.message_service.service.MessageService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Internal gRPC API for other services. Callers read the HTTP-style {@code status} field
 * (the convention in guild-service), so errors are returned in the body instead of as gRPC status codes.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class MessagesGrpcController extends MessagesServiceGrpc.MessagesServiceImplBase {

    private final MessageService messageService;

    @Override
    public void getUnreadCount(GetUnreadCountRequest request, StreamObserver<GetUnreadCountResponse> responseObserver) {
        GetUnreadCountResponse.Builder response = GetUnreadCountResponse.newBuilder();

        try {
            UUID channelId = parseUuid(request.getChannelId(), "Invalid channel ID");
            UUID lastReadId = request.getLastReadId().isBlank()
                    ? null
                    : parseUuid(request.getLastReadId(), "Invalid last read message ID");

            long count = messageService.getUnreadCount(channelId, lastReadId);
            response.setStatus(HttpStatus.OK.value()).setData((int) Math.min(count, Integer.MAX_VALUE));
        } catch (BadRequestException e) {
            log.warn("GetUnreadCount bad request: {}", e.getMessage());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        } catch (NotFoundException e) {
            log.warn("GetUnreadCount not found: {}", e.getMessage());
            response.setStatus(HttpStatus.NOT_FOUND.value());
        } catch (Exception e) {
            log.error("GetUnreadCount failed", e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private static UUID parseUuid(String value, String errorMessage) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(errorMessage);
        }
    }
}
