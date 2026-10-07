package com.viscord.message_service.controller;

import com.viscord.message_service.dto.CreateAttachmentRequest;
import com.viscord.message_service.dto.CreateAttachmentResponse;
import com.viscord.message_service.service.MessageService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bean validation on the request body only runs inside Spring MVC, so these checks need the real
 * MVC stack; MessageService is mocked.
 */
@WebMvcTest(ChannelAttachmentController.class)
class ChannelAttachmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageService messageService;

    private static String attachmentsPath(UUID channelId) {
        return "/channels/" + channelId + "/attachments";
    }

    private static String attachmentFileJson(int id) {
        return "{\"id\":" + id + ",\"fileName\":\"cat.png\",\"contentType\":\"image/png\",\"size\":1024}";
    }

    @Test
    @DisplayName("Happy path: valid attachment request returns 200 with the caller and channel from the header and path")
    void createAttachment_ValidRequest_ReturnsOk() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        Mockito.when(messageService.createAttachment(Mockito.any())).thenReturn(new CreateAttachmentResponse());

        mockMvc.perform(post(attachmentsPath(channelId))
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"files\":[" + attachmentFileJson(0) + "]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateAttachmentRequest> captor = ArgumentCaptor.forClass(CreateAttachmentRequest.class);
        Mockito.verify(messageService).createAttachment(captor.capture());
        Assertions.assertEquals(userId, captor.getValue().getUserId());
        Assertions.assertEquals(channelId, captor.getValue().getChannelId());
        Assertions.assertEquals(1, captor.getValue().getFiles().size());
    }

    static Stream<Arguments> invalidAttachmentRequests() {
        String elevenFiles = IntStream.range(0, 11)
                .mapToObj(ChannelAttachmentControllerTest::attachmentFileJson)
                .collect(Collectors.joining(","));

        return Stream.of(
                Arguments.of("no files", "{\"files\":[]}"),
                Arguments.of("more than 10 files", "{\"files\":[" + elevenFiles + "]}"),
                Arguments.of("zero size", "{\"files\":[{\"id\":0,\"fileName\":\"cat.png\",\"contentType\":\"image/png\",\"size\":0}]}"),
                Arguments.of("negative size", "{\"files\":[{\"id\":0,\"fileName\":\"cat.png\",\"contentType\":\"image/png\",\"size\":-1}]}"),
                Arguments.of("blank file name", "{\"files\":[{\"id\":0,\"fileName\":\" \",\"contentType\":\"image/png\",\"size\":1024}]}"),
                Arguments.of("missing content type", "{\"files\":[{\"id\":0,\"fileName\":\"cat.png\",\"size\":1024}]}")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAttachmentRequests")
    @DisplayName("Unhappy path: attachment request failing bean validation returns 400 without calling the service")
    void createAttachment_InvalidRequest_ReturnsBadRequest(String description, String body) throws Exception {
        mockMvc.perform(post(attachmentsPath(UUID.randomUUID()))
                        .header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("Unhappy path: missing X-User-Id header returns 400 without calling the service")
    void createAttachment_MissingUserHeader_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post(attachmentsPath(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"files\":[" + attachmentFileJson(0) + "]}"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(messageService);
    }
}
