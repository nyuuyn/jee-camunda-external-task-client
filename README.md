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
  - [EE Resource Injection with @Resource](#ee-resource-injection-with-resource)
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
│   ├── Dockerfile                      # 2-stage: configure WildFly + deploy EAR
│   ├── configure-wildfly.cli           # Adds PostgreSQL module, datasource & java:global/ bindings
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
├── notes-war/                          # WAR module – JAX-RS REST API
│   └── src/main/
│       ├── java/com/example/notes/web/
│       │   ├── JaxRsActivator.java     # @ApplicationPath("/api")
│       │   ├── JacksonConfig.java      # Registers JavaTimeModule for LocalDateTime
│       │   ├── NoteRequest.java        # Request DTO
│       │   └── NoteResource.java       # REST endpoints
│       └── webapp/WEB-INF/
│           └── web.xml                 # Minimal; resource-refs are declared in the EAR
│
└── notes-ear/                          # EAR module – packages everything for deployment
    └── src/main/application/META-INF/
        └── application.xml             # EAR descriptor: modules + java:app/ resource-refs
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

    /** Obtained via the standard java:comp/ binding — not CDI-injected. */
    @Resource(lookup = "java:comp/DefaultContextService")
    private ContextService contextService;   // Jakarta EE Concurrency

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

        // Wrap the handler with a contextual proxy so the EE container context
        // (classloader, security, naming) is available on Camunda's worker thread.
        client.subscribe("add-note")
              .handler(contextService.createContextualProxy(addNoteHandler, ExternalTaskHandler.class))
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

    // CDI constructor injection — these fields are final (set at construction time)
    private final NoteService noteService;
    private final CreatorContext creatorContext;
    private final RequestContextController requestContextController;

    // EE container injection — set after construction, cannot be final (see below)
    @Resource(lookup = "java:app/creator/taskHandler")
    private String taskHandlerCreatorName;

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

### EE Resource Injection with @Resource

Beyond CDI beans, Jakarta EE provides a second injection mechanism — `@Resource` — for
**container-managed resources** such as datasources, JMS destinations, and named
configuration values. The creator name strings use a **three-level lookup chain** that
separates the server-owned value, the EAR-level reference declaration, and the injection site.

#### Three-level lookup chain

```
@Resource(lookup="java:app/creator/restApi")
  └─► java:app/creator/restApi       ← resource-ref in application.xml (EAR scope)
        └─► java:global/creator/restApi  ← WildFly naming binding in standalone.xml (value)
```

- The **value** lives on the server and is never part of any build artifact.
- The **EAR** declares a typed reference visible to all its modules (`java:app/` namespace).
- The **WAR and EJB JARs** inject via `@Resource(lookup="java:app/...")` — no values in code.

#### Step 1 — Configure the value in WildFly (docker/configure-wildfly.cli)

The actual strings are registered in WildFly's naming subsystem at image-build time:

```
/subsystem=naming/binding="java:global\/creator\/restApi":add(
    binding-type=simple, type=java.lang.String, value="rest api"
)
/subsystem=naming/binding="java:global\/creator\/taskHandler":add(
    binding-type=simple, type=java.lang.String, value="task handler"
)
```

These entries are persisted in `standalone.xml` and can be changed via WildFly CLI at any
time without touching the EAR.

#### Step 2 — Declare resource-refs in application.xml (EAR scope)

`<resource-ref>` elements in `application.xml` register the references in the `java:app/`
namespace — the application-scoped JNDI namespace shared by **every module in the EAR**
(WAR, EJB JARs, and `WEB-INF/lib` JARs). `<lookup-name>` points to the server binding:

```xml
<resource-ref>
    <res-ref-name>java:app/creator/restApi</res-ref-name>
    <res-type>java.lang.String</res-type>
    <res-auth>Application</res-auth>
    <lookup-name>java:global/creator/restApi</lookup-name>
</resource-ref>
```

#### Step 3 — Inject with @Resource

The EE container resolves the absolute `java:app/` path, follows the `<lookup-name>` alias
to the server binding, and sets the field **after** the bean is constructed:

```java
// In NoteResource (JAX-RS resource, WAR CDI bean):
@Resource(lookup = "java:app/creator/restApi")
private String restApiCreatorName;   // set by EE container, not CDI

// In AddNoteHandler (@Dependent CDI bean, lives in WEB-INF/lib of the WAR):
@Resource(lookup = "java:app/creator/taskHandler")
private String taskHandlerCreatorName;

// In NoteService (@ApplicationScoped, EJB JAR) — both values injected for validation:
@Resource(lookup = "java:app/creator/restApi")
private String restApiCreatorName;

@Resource(lookup = "java:app/creator/taskHandler")
private String taskHandlerCreatorName;
```

`ContextService` is a standard Jakarta EE Concurrency resource and does not go through
the custom `java:app/` chain. It is obtained directly from WildFly's `java:comp/` namespace:

```java
// In CamundaClientStartup:
@Resource(lookup = "java:comp/DefaultContextService")
private ContextService contextService;
```

#### Coexistence of @Resource and @Inject in AddNoteHandler

`@Resource` and `@Inject` are processed by **different container subsystems** and can be
combined in the same class without conflict:

| Annotation | Processed by | Timing | Fields |
|---|---|---|---|
| `@Inject` (constructor) | CDI (Weld) | At bean instantiation | `final` — immutable |
| `@Resource` (field) | EE container | After construction | Non-`final` — set once, never changed |

#### Creator name validation in NoteService

`NoteService` itself also injects both creator name resources and uses them to validate the
value in `CreatorContext` before persisting a note:

```java
@ApplicationScoped
public class NoteService {

    @Resource(lookup = "java:app/creator/restApi")
    private String restApiCreatorName;

    @Resource(lookup = "java:app/creator/taskHandler")
    private String taskHandlerCreatorName;

    @Transactional
    public Note createNote(String title, String content) {
        String creatorName = creatorContext.getCreatorName();
        if (!Set.of(restApiCreatorName, taskHandlerCreatorName).contains(creatorName)) {
            throw new IllegalArgumentException("Invalid creator name: '" + creatorName + "'");
        }
        // … persist note
    }
}
```

Because the valid values are read from the same JNDI chain as the callers set them, the
service layer is the authoritative guard: any caller path that sets an unrecognised creator
name is rejected before a database write occurs.

#### Changing the values without recompiling

Because the actual value lives in WildFly's `standalone.xml`, it can be updated via the
WildFly CLI against a running server — no EAR rebuild or redeployment required:

```
/subsystem=naming/binding="java:global\/creator\/restApi":write-attribute(
    name=value, value="new name"
)
```

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

        creatorContext.setCreatorName(taskHandlerCreatorName); // value from @Resource / web.xml
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