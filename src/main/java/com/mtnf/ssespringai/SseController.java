package com.mtnf.ssespringai;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@Slf4j
public class SseController {
    private final ChatClient chatClient;

    public SseController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamChat(@RequestBody ChatRequest chatRequest) {

        return chatClient.prompt().user(chatRequest.content()).stream().chatResponse()
                .mapNotNull(SseController::build)
                .concatWith(Mono.just(ServerSentEvent.builder().data(new ChatDone(0L)).event("done").build()))
                .onErrorResume(error -> Mono.just(ServerSentEvent.builder().data(new ChatError(error.getMessage())).event("error").build()))
                .doOnCancel(() -> log.info("Chat message stream cancelled"))
                .doOnComplete(() -> log.info("Chat message stream completed"));

    }

    private static @Nullable ServerSentEvent<Object> build(ChatResponse c) {
        String s = Optional.ofNullable(c.getResult()).map(Generation::getOutput).map(AbstractMessage::getText).orElse(null);

        // Chunks without text (e.g. the final metadata-only chunk) produce no token event.
        // Only skip null/empty: a whitespace-only chunk such as " " is real content.
        if (s == null || s.isEmpty()) {
            return null;
        }

        return ServerSentEvent.<Object>builder(new TestChatResponse(s)).event("token").build();
    }

    public record ChatRequest(String content) {
    }

    public record TestChatResponse(String content) {
    }

    public record ChatDone(Long totalTokensUsed) {
    }

    public record ChatError(String errorMessage) {
    }
}
