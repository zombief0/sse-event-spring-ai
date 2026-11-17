# sse-spring-ai

A streaming chat backend built with **Spring Boot 4**, **Spring AI 2.0** and **WebFlux**. It forwards a user's message to an LLM on [Groq](https://groq.com) and streams the answer back to the client token by token, using **Server-Sent Events (SSE)**.

> **Status: work in progress.** This is the backend skeleton for week 3 of my AI Product Engineer roadmap (Project 1: streaming AI chat). The Angular client is not built yet, and several production concerns are still open (see [Roadmap](#roadmap)).

## Why this project exists

LLMs take seconds to finish an answer but produce the first token almost immediately. Streaming trades nothing in total time and a lot in *time to first token*: the user starts reading while the model is still generating, and can stop a wrong answer early.

This project is where I learn the full streaming chain:

```
Browser ── POST /api/chat/stream ──▶ WebFlux controller ──▶ Spring AI ChatClient ──▶ Groq
   ▲                                        │
   └──────── text/event-stream ◀────────────┘
             token · token · … · done   (or error)
```

Cancelling in the browser closes the connection, which cancels the Reactor subscription, which closes the upstream call to the provider, so tokens nobody reads are not generated.

## Tech stack

| Area | Choice |
| --- | --- |
| Language | Java 25 |
| Framework | Spring Boot 4.1.2, **WebFlux** (reactive stack) |
| AI | Spring AI 2.0.1 (`spring-ai-starter-model-openai`, pointed at Groq's OpenAI-compatible API) |
| Model | `openai/gpt-oss-120b` on Groq (configurable) |
| Build | Maven (wrapper included) |

Spring AI's streaming API is only supported on the reactive stack, which is why this project uses WebFlux instead of Spring MVC.

## Getting started

### Prerequisites

- JDK 25
- A [Groq API key](https://console.groq.com)

### Configure

The API key is read from an environment variable. Never put it in a file in the repository.

```bash
export GROQ_API_KEY=your-key-here
```

The provider settings live in [`src/main/resources/application.yaml`](src/main/resources/application.yaml): the Groq base URL (`https://api.groq.com/openai/v1`) and the model name. Check Groq's [supported models](https://console.groq.com/docs/models) page before changing the model.

### Run

```bash
./mvnw spring-boot:run
```

The server starts on `http://localhost:8080`. The startup log should show Netty, which confirms the reactive stack is active.

### Try it

```bash
curl -N -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" -d '{"content":"Explain server-sent events in three sentences."}'
```

`-N` disables curl's own buffering so you can watch events arrive one by one. Postman also works, but it may parse events for you; use its raw view if you want to see the exact bytes.

## API

### `POST /api/chat/stream`

**Request** (`application/json`):

| Field | Type | Description |
| --- | --- | --- |
| `content` | string | The user's message |

**Response** (`text/event-stream`): a sequence of named events.

| Event | When | Payload |
| --- | --- | --- |
| `token` | For each chunk of generated text | `{"content": "<text>"}` |
| `done` | After the last token, on success only | `{"totalTokensUsed": <number>}` |
| `error` | If the stream fails | `{"errorMessage": "<message>"}`; the stream then closes |

Example on the wire:

```
event:token
data:{"content":"Server"}

event:token
data:{"content":"-sent"}

event:done
data:{"totalTokensUsed":0}

```

Design notes:

- **Payloads are JSON on purpose.** The SSE spec strips one leading space after `data:`, and LLM tokens often start with a space (`" world"`). Sending raw text would glue words together. Inside a JSON string, the space survives.
- **`done` makes completion explicit.** HTTP 200 is already sent when streaming starts, so a failure cannot become a 500. A client that sees the stream end without `done` or `error` should treat the answer as truncated.
- **Chunks without text are skipped.** Metadata-only chunks produce no token event. Whitespace-only chunks are kept because they are real content.

## Project structure

```
src/main/java/com/mtnf/ssespringai/
├── SseSpringAiApplication.java   # Spring Boot entry point
└── SseController.java            # Streaming endpoint and event payload records
src/main/resources/
└── application.yaml              # Groq provider configuration
```

## Roadmap

Done:

- [x] WebFlux streaming endpoint over SSE
- [x] Groq through Spring AI's OpenAI-compatible client
- [x] Named events (`token`, `done`, `error`) with JSON payloads
- [x] Skip empty chunks, with logging of cancel and completion

Next:

- [ ] Log the real exception on failure and send the client a generic error message
- [ ] Fill `done` with the real model name and token usage (first check whether Groq streams usage)
- [ ] Validate the request (blank or missing message returns 400 before the stream starts)
- [ ] Heartbeat comments, and proxy settings (no buffering, longer timeouts) for deployment
- [ ] Restrict CORS (it is currently open for development) or use an Angular dev proxy
- [ ] Make the context-loading test pass without a real `GROQ_API_KEY`
- [ ] Move chat logic into a service; add per-request cost and latency logging (week 4)
- [ ] Angular client: read the stream, append chunks with signals, Stop button, skeletons, error and partial states

## Notes and limitations

- CORS is configured with a wildcard on the controller for local development only. Do not deploy it that way.
- The `done` payload currently contains a placeholder token count.
- On plain `http://localhost` the server speaks HTTP/1.1, where browsers allow only about six connections per domain. Several tabs with open streams can stall; HTTP/2 behind HTTPS removes the limit.

## Further reading

- [MDN: Using server-sent events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [Spring AI: Chat Client API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Spring AI: Groq chat](https://docs.spring.io/spring-ai/reference/api/chat/groq-chat.html)
- [Reactor: Flux](https://projectreactor.io/docs/core/release/reference/coreFeatures/flux.html)
