package com.viscord.message_service.controller;

import com.viscord.message_service.exception.NotFoundException;
import com.viscord.message_service.grpc.GetUnreadCountRequest;
import com.viscord.message_service.grpc.GetUnreadCountResponse;
import com.viscord.message_service.service.MessageService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class MessagesGrpcControllerTest {
    @Mock
    private MessageService messageService;

    @Mock
    private StreamObserver<GetUnreadCountResponse> responseObserver;

    @InjectMocks
    private MessagesGrpcController controller;

    private GetUnreadCountResponse call(GetUnreadCountRequest request) {
        controller.getUnreadCount(request, responseObserver);

        ArgumentCaptor<GetUnreadCountResponse> captor = ArgumentCaptor.forClass(GetUnreadCountResponse.class);
        Mockito.verify(responseObserver).onNext(captor.capture());
        Mockito.verify(responseObserver).onCompleted();
        return captor.getValue();
    }

    @Test
    @DisplayName("Happy path: given empty last read ID, should return count with status 200")
    void getUnreadCount_EmptyLastRead_ReturnsOk() {
        UUID channelId = UUID.randomUUID();
        Mockito.when(messageService.getUnreadCount(channelId, null)).thenReturn(5L);

        GetUnreadCountResponse response = call(GetUnreadCountRequest.newBuilder()
                .setChannelId(channelId.toString())
                .build());

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals(5, response.getData());
    }

    @Test
    @DisplayName("Unhappy path: given malformed channel ID, should return status 400")
    void getUnreadCount_MalformedChannelId_ReturnsBadRequest() {
        GetUnreadCountResponse response = call(GetUnreadCountRequest.newBuilder()
                .setChannelId("not-a-uuid")
                .build());

        Assertions.assertEquals(400, response.getStatus());
        Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("Unhappy path: given unknown last read message, should return status 404")
    void getUnreadCount_UnknownLastRead_ReturnsNotFound() {
        UUID channelId = UUID.randomUUID();
        UUID lastReadId = UUID.randomUUID();
        Mockito.when(messageService.getUnreadCount(channelId, lastReadId)).thenThrow(new NotFoundException("not found"));

        GetUnreadCountResponse response = call(GetUnreadCountRequest.newBuilder()
                .setChannelId(channelId.toString())
                .setLastReadId(lastReadId.toString())
                .build());

        Assertions.assertEquals(404, response.getStatus());
    }
}
