package com.viscord.message_service.controller;

import com.viscord.message_service.dto.CreateMessageRequest;
import com.viscord.message_service.dto.MessageResponse;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real MVC stack (argument resolution, message converters, GlobalExceptionHandler)
 * with MessageService mocked, so framework-level errors are checked end to end.
 */
@WebMvcTest(ChannelMessageController.class)
class ChannelMessageControllerTest {

    private static final String DATA_JSON = "{\"content\":\"hello\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageService messageService;

    private String messagesPath() {
        return "/channels/" + UUID.randomUUID() + "/messages";
    }

    @Test
    @DisplayName("Happy path: JSON data part is bound and the message is created")
    void createMessage_JsonDataPart_ReturnsCreated() throws Exception {
        Mockito.when(messageService.createMessageWithUploads(Mockito.any(), Mockito.any())).thenReturn(new MessageResponse());
        MockMultipartFile data = new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, DATA_JSON.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(messagesPath()).file(data).header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isCreated());

        Mockito.verify(messageService, Mockito.never()).createMessage(Mockito.any());
    }

    @Test
    @DisplayName("Happy path: JSON body with attachment keys goes to the presigned-upload flow")
    void createMessage_JsonBody_ReturnsCreated() throws Exception {
        UUID userId = UUID.randomUUID();
        Mockito.when(messageService.createMessage(Mockito.any())).thenReturn(new MessageResponse());

        mockMvc.perform(post(messagesPath())
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\",\"attachments\":[{\"key\":\"pending/" + userId + "/a.png\",\"fileName\":\"a.png\"}]}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateMessageRequest> captor = ArgumentCaptor.forClass(CreateMessageRequest.class);
        Mockito.verify(messageService).createMessage(captor.capture());
        Assertions.assertEquals(userId, captor.getValue().getSenderId());
        Assertions.assertEquals(1, captor.getValue().getAttachments().size());
        Mockito.verify(messageService, Mockito.never()).createMessageWithUploads(Mockito.any(), Mockito.any());
    }

    static Stream<Arguments> invalidJsonMessages() {
        String elevenAttachments = IntStream.range(0, 11)
                .mapToObj(i -> "{\"key\":\"pending/u/" + i + ".png\",\"fileName\":\"" + i + ".png\"}")
                .collect(Collectors.joining(","));

        return Stream.of(
                Arguments.of("blank key", "{\"attachments\":[{\"key\":\" \",\"fileName\":\"a.png\"}]}"),
                Arguments.of("missing file name", "{\"attachments\":[{\"key\":\"pending/u/a.png\"}]}"),
                Arguments.of("more than 10 attachments", "{\"attachments\":[" + elevenAttachments + "]}")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidJsonMessages")
    @DisplayName("Unhappy path: JSON message failing bean validation returns 400 without calling the service")
    void createMessage_InvalidJson_ReturnsBadRequest(String description, String body) throws Exception {
        mockMvc.perform(post(messagesPath())
                        .header("X-User-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("Unhappy path: data part without a JSON content type returns 415, not 500")
    void createMessage_UntypedDataPart_ReturnsUnsupportedMediaType() throws Exception {
        MockMultipartFile data = new MockMultipartFile("data", "", MediaType.APPLICATION_OCTET_STREAM_VALUE, DATA_JSON.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(messagesPath()).file(data).header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").isNotEmpty());

        Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("Unhappy path: malformed JSON in the data part returns 400")
    void createMessage_MalformedJson_ReturnsBadRequest() throws Exception {
        MockMultipartFile data = new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, "{not json".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart(messagesPath()).file(data).header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Unhappy path: missing X-User-Id header returns 400")
    void getChannelMessages_MissingUserHeader_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get(messagesPath()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("Unhappy path: non-UUID channel ID returns 400")
    void getChannelMessages_InvalidChannelId_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/channels/not-a-uuid/messages").header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
