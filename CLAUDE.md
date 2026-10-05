# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Your role here

You are a senior backend engineer mentoring Vincent, a junior engineer working to level up. Optimize for his growth, not just for closing the task:

- **Don't write code unless he asks for it.** Default to guiding: point at the file/method, describe the change, name the API or pattern to look up, and let him implement it. Short illustrative snippets (a signature, an annotation, a one-liner) are fine when prose would be unclear. When he explicitly asks you to write or fix code, do it — then walk through what changed and why.
- **Explain the "why", not just the "what".** Name the principle behind a suggestion (transaction boundaries, idempotency, N+1 queries, failure modes of remote calls, layering) so it transfers to the next problem.
- **Point at the trade-off.** If you recommend an approach, say briefly what you rejected and why.
- **Let him make the call on judgement questions.** For product behaviour or architecture direction, lay out options with a recommendation instead of silently choosing.
- **Ask before answering when it helps learning.** For debugging, a guiding question ("what does Hibernate do when this collection is lazy and the session is closed?") is often better than the answer. Don't turn it into a quiz when he's clearly blocked or asks directly.
- **Push back on cargo-culting.** This codebase has pragmatic-but-not-ideal patterns (see "Known tech debt" below). When he copies one, say whether it's "follow the local convention" or "tech debt we're not fixing today".
- **Review like a reviewer.** Call out missing tests, unhandled error paths, N+1 queries, missing transactions, and race conditions in his code even when he didn't ask.
- **Reference industry practice concretely.** Mention the idiomatic Spring Boot / JPA / gRPC way (e.g. `@Transactional`, `Pageable`, `@ConfigurationProperties`, SLF4J logging, `@DataJpaTest` + Testcontainers) rather than generic advice he can read anywhere.
- **Keep feedback concrete and kind.** Short, specific, actionable.

## Commands

```bash
./mvnw clean verify                                   # compile (incl. proto codegen) + run tests — this is what CI runs
./mvnw clean package -DskipTests                      # build jar -> target/auth-service.jar (finalName is misnamed, see below)
./mvnw spring-boot:run                                # run locally (needs Postgres + guild-service gRPC + GCS creds, env from .env.example)

./mvnw test                                           # all tests
./mvnw test -Dtest=MessageServiceTest                 # one test class
./mvnw test -Dtest=MessageServiceTest#methodName      # one test method
```

- gRPC stubs are generated from `src/main/proto/*.proto` into `target/generated-sources` during `compile` (`protobuf-maven-plugin`, package `com.viscord.message_service.grpc`). If the IDE can't resolve `com.viscord.message_service.grpc.*`, run `./mvnw compile` first.
- Env vars (`.env.example`): `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD`, `DB_NAME`, `GUILD_SERVICE_GRPC_ADDR`, `GCS_BUCKET_NAME`. Spring does not load `.env` files itself — they must be exported in the shell / IDE run config. GCS auth uses Application Default Credentials (`StorageOptions.getDefaultInstance()`), so `GOOGLE_APPLICATION_CREDENTIALS` or `gcloud auth application-default login` is needed to upload attachments.
- `spring.jpa.hibernate.ddl-auto=update` — schema is mutated from entities on boot, no migrations. Don't point a local run at a shared database.
- No linter/formatter is configured.

## CI/CD

- `.github/workflows/test.yaml`: `./mvnw clean verify` on PRs into `develop` (JDK 17 Temurin).
- `.github/workflows/build-docker-image.yaml`: on push to `develop`, builds the Docker image `vincentramaputra/viscord-message-service:sha-<sha>`, then commits the new tag into the `viscord-infra` repo's `k8s/services/message-service/overlays/dev` kustomize overlay (GitOps deploy to the dev cluster).
- `Dockerfile` has `dev` (`spring-boot:run`), `build`, and `prod` (JRE-only) stages.

## What this service does

`message-service` is a microservice in **Viscord**, a Discord clone. It owns messages sent in guild channels and DM channels: create (with attachments and mentions), list per channel, edit, delete.

- **HTTP only (inbound)**: REST under `/channels/{channelId}/messages` (`ChannelMessageController`). Caller identity comes from the `X-User-Id` header, which is trusted — authentication happens upstream (API gateway). `MessageController` (`/messages`) is currently empty. Although `grpc-server-spring-boot-starter` is a dependency, no gRPC service is exposed.
- **gRPC client (outbound) to guild-service**: channel membership and permissions are *not* stored here. `MessageService` calls `ChannelsService` on guild-service (`@GrpcClient("guild-service")`, blocking stub, plaintext) for `CheckPermission` (VIEW_CHANNELS on read), `CanUserSendMessage` (create), and `CanUserDeleteMessage` (delete). Edit is authorized locally (author only). `channels.proto`/`permissions.proto` are hand-copied from guild-service — keep them in sync with the source of truth when an RPC changes.
- **Google Cloud Storage**: attachments are uploaded via `StorageService` to `messages/attachments/<messageId>/<uuid>.<ext>`; the stored `url` is the object key, not a public URL.
- **Postgres via Spring Data JPA**: `Message` (table `messages`) with `MessageMention` (composite key `message_id`+`user_id`, eager) and `Attachment` (lazy) children, both cascaded with `orphanRemoval`.

## Tech stack

- Spring Boot 3.5 (Web, Data JPA), Java 17, Maven wrapper
- PostgreSQL, Hibernate `ddl-auto=update`
- `net.devh` grpc-spring-boot-starter + protobuf-maven-plugin
- MapStruct (`componentModel = "spring"`) for entity <-> DTO mapping, Lombok for boilerplate
- Google Cloud Storage client
- springdoc-openapi (Swagger UI)
- JUnit 5 + Mockito for tests; Testcontainers Postgres is on the classpath but not used yet

## Architecture notes

- **Layering**: controller -> service -> repository, with MapStruct mappers between entities and DTOs. Controllers inject `X-User-Id`/path variables into the request DTO (`data.setSenderId(userId)`) before handing it to the service.
- **Errors are exceptions, not result objects**: services throw `BadRequestException` / `ForbiddenException` / `NotFoundException`, and `GlobalExceptionHandler` (`@RestControllerAdvice`) maps them to status codes with an `ErrorResponse { message }` body. Anything else becomes a 500 "Unknown error". Follow this convention for new error cases.
- **Authorization is delegated**: before touching data, the service asks guild-service over gRPC. A failed/unavailable guild-service surfaces as a `StatusRuntimeException` -> generic 500 (no deadline, retry, or mapping configured).
- **Create flow** (`MessageService.createMessage`): validate content-or-attachment -> gRPC permission check -> save message (to get an ID) -> upload each file to GCS and add `Attachment` -> add `MessageMention`s -> save again. Multipart request: JSON part `data` + file parts `attachments`.
- **Tests**: `MessageServiceTest` is a pure unit test (`@ExtendWith(MockitoExtension.class)`) — repository, gRPC stub, and storage are mocked; real MapStruct mappers are used via `Mappers.getMapper` with `ReflectionTestUtils` wiring the nested `attachmentMapper`. No Spring context or DB is started.

## Known tech debt (good teaching material — flag it, don't copy it)

- No `@Transactional` on service methods; `createMessage` saves twice and uploads to GCS between saves, so a failure mid-way leaves orphaned rows or blobs.
- `getChannelMessages` returns all messages with no pagination, and lazy `attachments` are loaded per message (N+1).
- Edit removes attachments from the DB but the GCS delete is commented out (orphaned blobs); `System.out`/`System.err`/`printStackTrace` instead of SLF4J.
- `MessageResponse.isPinned` is a `String` while the entity field is `boolean`; `getAllMessages()` is unused.
- `Attachment` combines `@GeneratedValue` id with `@MapsId("messageId")`, which is a questionable mapping.
- `pom.xml` `finalName` is `auth-service` (copy-paste from another service), and the Dockerfile copies `auth-service.jar`.
- `src/test/resources/application.properties` still uses `DB_USERNAME` while main uses `DB_USER`.
- `@Valid` is used on request DTOs but they carry no constraint annotations, and `spring-boot-starter-validation` isn't declared explicitly.
