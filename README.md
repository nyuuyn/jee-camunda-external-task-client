# JEE Notes App with Camunda 7 External Task Client

A Maven multi-module Jakarta EE 10 application running on WildFly 32 that demonstrates how to
integrate a **Camunda 7 External Task Client** inside a Jakarta EE application server — including
proper CDI bean injection into external task handlers and how to bridge the gap between
managed (servlet) request contexts and unmanaged (Camunda worker) threads.

---

## Table of Contents

- [Project Structure](#project-structure)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
- [REST API](#rest-api)
- [Camunda 7 External Task Integration](#camunda-7-external-task-integration)
  - [Architecture Overview](#architecture-overview)
  - [How the External Task Client is bootstrapped](#how-the-external-task-client-is-bootstrapped)
  - [CDI Injection in External Task Handlers](#cdi-injection-in-external-task-handlers)
  - [The Request Context Problem on Unmanaged Threads](#the-request-context-problem-on-unmanaged-threads)
  - [Solving it with RequestContextController](#solving-it-with-requestcontextcontroller)
  - [Which CDI Scopes are Safe on Unmanaged Threads](#which-cdi-scopes-are-safe-on-unmanaged-threads)
  - [The BPMN Process](#the-bpmn-process)
  - [Triggering a Process Instance](#triggering-a-process-instance)

---

## Project Structure

```
.
├── docker-compose.yml                  # Starts postgres, camunda, wildfly
├── docker/
│   ├── Dockerfile                      # 2-stage: configure WildFly + deploy WAR
│   ├── configure-wildfly.cli           # Adds PostgreSQL module & datasource
│   └── postgres-init/
│       └── 01-create-camundadb.sh      # Idempotently creates camundadb on first start
│
├── notes-ejb/                          # EJB module – domain model & business logic
│   └── src/main/java/com/example/notes/
│       ├── entity/Note.java            # JPA entity
│       └── service/
│           ├── NoteService.java        # @ApplicationScoped CDI bean (DB writes)
│           └── CreatorContext.java     # @RequestScoped CDI bean (carries creator name)
│
├── notes-camunda-client/               # Camunda External Task Client module
│   └── src/main/
│       ├── java/com/example/notes/camunda/
│       │   ├── CamundaClientStartup.java   # @Singleton @Startup EJB – bootstraps the client
│       │   └── AddNoteHandler.java         # @Dependent ExternalTaskHandler implementation
│       └── resources/
│           ├── add-note-process.bpmn       # BPMN process definition
│           └── META-INF/ejb-jar.xml        # Marker: tells WildFly to scan JAR for EJBs
│
└── notes-war/                          # WAR module – JAX-RS REST API
    └── src/main/java/com/example/notes/web/
        ├── JaxRsActivator.java         # @ApplicationPath("/api")
        ├── JacksonConfig.java          # Registers JavaTimeModule for LocalDateTime
        ├── NoteRequest.java            # Request DTO
        └── NoteResource.java           # REST endpoints
```

---

## Technology Stack

| Component | Technology |
|---|---|
| Application Server | WildFly 32 (Jakarta EE 10) |
| Language | Java 17 |
| Build | Maven 3 (multi-module) |
| Persistence | JPA / Hibernate with PostgreSQL |
| REST API | Jakarta RESTful Web Services (JAX-RS) via RESTEasy |
| CDI | Weld (bundled with WildFly) |
| Process Engine | Camunda 7 (`camunda/camunda-bpm-platform:7.21.0`) |
| External Task Client | `org.camunda.bpm:camunda-external-task-client:7.21.0` |
| Database | PostgreSQL 16 |
| Containerization | Docker / Docker Compose |

---

## Getting Started

### Prerequisites

- JDK 17+
- Maven 3.8+
- Docker with Docker Compose

### Build

```bash
mvn clean package
```

This produces `notes-war/target/notes.war`, which is picked up by the Dockerfile.

### Start all services

```bash
docker compose up --build
```

> **First run / schema reset:** If you are starting fresh or have added columns to the JPA
> entities, wipe the postgres volume first so the schema is recreated:
>
> ```bash
> docker compose down -v
> docker compose up --build
> ```

### Service endpoints

| Service | URL |
|---|---|
| Notes REST API | http://localhost:8080/notes/api/notes |
| WildFly Management | http://localhost:9990 |
| Camunda Web App | http://localhost:8090/camunda (demo/demo) |
| Camunda REST API | http://localhost:8090/engine-rest |
| PostgreSQL | localhost:5432 |

---

## REST API

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/notes` | List all notes |
| `GET` | `/api/notes/{id}` | Get one note |
| `POST` | `/api/notes` | Create a note |
| `PUT` | `/api/notes/{id}` | Update a note |
| `DELETE` | `/api/notes/{id}` | Delete a note |

**Create a note via REST:**

```bash
curl -X POST http://localhost:8080/notes/api/notes \
  -H "Content-Type: application/json" \
  -d '{"title": "Hello", "content": "Created via REST"}'
```

The persisted note will have `creatorName = "rest api"`.

---

## Camunda 7 External Task Integration

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                     WildFly 32                          │
│                                                         │
│  ┌─────────────────┐      ┌─────────────────────────┐  │
│  │  NoteResource   │      │  CamundaClientStartup   │  │
│  │  (JAX-RS)       │      │  @Singleton @Startup    │  │
│  └────────┬────────┘      └──────────┬──────────────┘  │
│           │ @Inject                  │ @Inject          │
│           ▼                          ▼                  │
│  ┌─────────────────────────────────────────────────┐   │
│  │              NoteService                        │   │
│  │              @ApplicationScoped                 │   │
│  │  @Inject CreatorContext (proxy)                 │   │
│  └─────────────────────────────────────────────────┘   │
│           ▲                          ▲                  │
│  servlet  │                          │ RequestContext-   │
│  request  │                          │ Controller        │
│  context  │                          │ (synthetic)       │
│  (auto)   │                          │                  │
│  ┌────────┴────────┐      ┌──────────┴──────────────┐  │
│  │  CreatorContext │      │  CreatorContext          │  │
│  │  "rest api"     │      │  "task handler"          │  │
│  │  @RequestScoped │      │  @RequestScoped          │  │
│  └─────────────────┘      └─────────────────────────┘  │
│                                       ▲                 │
│                            ┌──────────┴──────────────┐  │
│                            │    AddNoteHandler        │  │
│                            │    @Dependent            │  │
│                            └──────────────────────────┘  │
│                                       ▲                 │
└───────────────────────────────────────┼─────────────────┘
                                        │ long-poll
                              ┌─────────┴──────────┐
                              │  Camunda Engine     │
                              │  (separate container│
                              │  port 8090)         │
                              └────────────────────-┘
```

---

### How the External Task Client is bootstrapped

The entry point is `CamundaClientStartup`, an EJB `@Singleton` annotated with `@Startup`.
WildFly instantiates it immediately after the application is deployed and calls `@PostConstruct`.

```java
@Singleton
@Startup
public class CamundaClientStartup {

    @Inject
    private AddNoteHandler addNoteHandler;   // CDI-managed, see below

    @Resource
    private ManagedExecutorService executor; // Jakarta EE Concurrency

    private ExternalTaskClient client;

    @PostConstruct
    public void start() {
        // Run on a managed thread to avoid blocking EJB deployment
        executor.submit(() -> {
            waitForCamunda();   // polls engine-rest/engine until 200 OK
            deployProcess();    // POST BPMN to /deployment/create via HTTP
            startClient();
        });
    }

    private void startClient() {
        client = ExternalTaskClient.create()
                .baseUrl("http://camunda:8080/engine-rest")
                .asyncResponseTimeout(10_000)
                .lockDuration(10_000)
                .build();

        client.subscribe("add-note")
              .handler(addNoteHandler)  // the CDI-managed @Dependent bean
              .open();
    }
}
```

**Why `ManagedExecutorService` instead of a plain `Thread`?**
The EJB specification forbids EJBs from spawning unmanaged threads directly.
`ManagedExecutorService` (Jakarta EE Concurrency) is the spec-compliant way to run background
work from inside an EJB. It is injected with `@Resource` and bound to WildFly's default
managed thread pool.

---

### CDI Injection in External Task Handlers

The Camunda External Task Client is a plain Java library. `ExternalTaskHandler` is just a
functional interface — its `execute()` method is called by Camunda's internal thread pool,
which knows nothing about Jakarta EE or CDI.

This creates the central design question:

> *How do we get CDI-managed beans (like `NoteService`) into a handler that is called from
> outside the container's control?*

**The answer: make the handler itself a CDI bean.**

By annotating `AddNoteHandler` with `@Dependent` and using constructor injection with `@Inject`,
CDI creates and wires the handler. `CamundaClientStartup` receives the fully-injected instance
and hands it to the External Task Client:

```java
@Dependent  // ← CDI manages this class; no proxy needed (see below)
public class AddNoteHandler implements ExternalTaskHandler {

    private final NoteService noteService;
    private final CreatorContext creatorContext;
    private final RequestContextController requestContextController;

    @Inject  // ← CDI constructor injection
    public AddNoteHandler(NoteService noteService,
                          CreatorContext creatorContext,
                          RequestContextController requestContextController) {
        this.noteService              = noteService;
        this.creatorContext           = creatorContext;
        this.requestContextController = requestContextController;
    }

    @Override
    public void execute(ExternalTask task, ExternalTaskService service) { ... }
}
```

**Why `@Dependent` and not `@ApplicationScoped`?**

| | `@Dependent` | `@ApplicationScoped` |
|---|---|---|
| CDI proxy required | **No** | Yes |
| No-arg constructor needed | **No** | Yes (for proxy subclass) |
| Constructor injection | Clean – `final` fields | Needs extra protected no-arg ctor |
| Instance count | One per injection point | One per application |
| Lifecycle | Tied to owning `@Singleton` EJB | Application lifetime |

`@Dependent` is the better fit: the handler is stateless, owns no resources, and lives
exactly as long as `CamundaClientStartup` (which is the entire application lifetime anyway).
Constructor injection with `final` fields is cleaner and the intent is clear.

---

### The Request Context Problem on Unmanaged Threads

Consider `CreatorContext`, a `@RequestScoped` CDI bean that carries the creator name:

```java
@RequestScoped
public class CreatorContext {
    private String creatorName;
    // getter + setter
}
```

When CDI injects a `@RequestScoped` bean anywhere, it **always** injects a **proxy**, never
the real instance. At the moment a method is called on the proxy, CDI looks up the currently
active `@RequestScoped` context on the **calling thread** to find (or create) the real bean
instance.

```
Proxy.getCreatorName()
  └─► CDI runtime: "Which @RequestScoped instance is active on THIS thread?"
          ├─ Servlet thread  → servlet container opened a context → ✅ found
          └─ Camunda thread  → no context was ever opened         → ❌ ContextNotActiveException
```

The scope of `AddNoteHandler` itself (`@Dependent`, `@ApplicationScoped`, etc.) makes **no
difference** here. The exception is thrown inside the proxy of `CreatorContext`, not inside
the handler. Every scoped proxy for a short-lived scope (`@RequestScoped`, `@SessionScoped`,
`@ConversationScoped`) has this problem on unmanaged threads.

---

### Solving it with RequestContextController

CDI 2.0 (Jakarta EE 9+) introduced
`jakarta.enterprise.context.control.RequestContextController`, a portable API that lets
application code manually activate and deactivate a request context on any thread.

```java
@Override
public void execute(ExternalTask task, ExternalTaskService service) {

    requestContextController.activate(); // ← opens a request context on this thread
    try {

        creatorContext.setCreatorName("task handler");
        // Now the proxy for CreatorContext resolves correctly because
        // there IS an active request context on this thread.

        Note note = noteService.createNote(
            task.getVariable("title"),
            task.getVariable("content")
        );
        service.complete(task, Map.of("noteId", note.getId()));

    } catch (Exception e) {
        service.handleFailure(task, "Note creation failed", e.getMessage(), 0, 0L);
    } finally {
        requestContextController.deactivate(); // ← destroys the @RequestScoped instance
    }
}
```

This mirrors exactly what the servlet container does for an HTTP request:

```
HTTP request arrives
  └─► container activates @RequestScoped context
        └─► servlet / JAX-RS resource runs
              └─► container deactivates context, @RequestScoped beans destroyed
```

```
Camunda task arrives
  └─► handler calls requestContextController.activate()
        └─► handler logic runs
              └─► handler calls requestContextController.deactivate()
```

**`RequestContextController` is itself injectable** (it is a built-in CDI bean), so it fits
naturally into constructor injection alongside the other dependencies.

---

### Which CDI Scopes are Safe on Unmanaged Threads

| Scope | Safe without `RequestContextController`? | Reason |
|---|---|---|
| `@ApplicationScoped` | ✅ Yes | Context tied to application lifetime — always active |
| `@Dependent` | ✅ Yes | No context lookup at call time — bound to the owning instance |
| `@RequestScoped` | ❌ No | Requires an active request context on the calling thread |
| `@SessionScoped` | ❌ No | Requires an active HTTP session context |
| `@ConversationScoped` | ❌ No | Requires an active conversation context |

**Rule of thumb:** If you need `@RequestScoped` (or any other short-lived scope) in an
external task handler, wrap your handler logic with `RequestContextController.activate()` /
`deactivate()` as shown above.

---

### The BPMN Process

`add-note-process.bpmn` defines a minimal process with a single External Service Task:

```
┌─────────┐     ┌──────────────────────────────────┐     ┌───────┐
│  Start  │────►│  Add Note                        │────►│  End  │
│  Event  │     │  camunda:type="external"          │     │ Event │
└─────────┘     │  camunda:topic="add-note"         │     └───────┘
                └──────────────────────────────────-┘
```

- `camunda:type="external"` tells Camunda not to execute this task itself but to expose it
  as an external task that a worker can lock and complete.
- `camunda:topic="add-note"` is the subscription key. The External Task Client subscribes to
  exactly this topic name.
- `camunda:historyTimeToLive="P1D"` (ISO 8601 duration) is required by Camunda 7.17+ when
  history cleanup enforcement is active.

The process is deployed to Camunda's REST API by `CamundaClientStartup` at application startup.
The `deploy-changed-only=true` flag means re-deployments are skipped if the BPMN has not changed.

---

### Triggering a Process Instance

Start a new process instance via the Camunda REST API, passing the note's title and content as
process variables:

```bash
curl -X POST http://localhost:8090/engine-rest/process-definition/key/add-note-process/start \
  -H "Content-Type: application/json" \
  -d '{
    "variables": {
      "title":   {"value": "Hello Camunda", "type": "String"},
      "content": {"value": "Created via BPMN process", "type": "String"}
    }
  }'
```

The External Task Client in WildFly picks up the task within seconds, calls
`AddNoteHandler.execute()`, which persists the note with `creatorName = "task handler"`.
The completed `noteId` is returned as an output process variable.

Verify the result:

```bash
curl http://localhost:8080/notes/api/notes
```