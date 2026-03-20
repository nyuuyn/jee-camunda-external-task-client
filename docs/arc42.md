# arc42 Architecture Documentation

**JEE Notes App with Camunda 7 External Task Client**

| Attribute | Value |
|---|---|
| Version | 1.0 |
| Date | 2026-03-20 |
| Status | Living Document |

---

## Table of Contents

1. [Introduction and Goals](#1-introduction-and-goals)
2. [Architecture Constraints](#2-architecture-constraints)
3. [System Scope and Context](#3-system-scope-and-context)
4. [Solution Strategy](#4-solution-strategy)
5. [Building Block View](#5-building-block-view)
6. [Runtime View](#6-runtime-view)
7. [Deployment View](#7-deployment-view)
8. [Crosscutting Concepts](#8-crosscutting-concepts)
9. [Architecture Decisions](#9-architecture-decisions)
10. [Quality Requirements](#10-quality-requirements)
11. [Risks and Technical Debt](#11-risks-and-technical-debt)
12. [Glossary](#12-glossary)

---

## 1. Introduction and Goals

### 1.1 Requirements Overview

The system is a **notes management application** that allows clients to create, read, update,
and delete short text notes. Notes can be created through two distinct entry points:

1. **Directly via a REST API** — an HTTP client posts a note payload to a JAX-RS endpoint.
2. **Via a BPMN process** — a Camunda 7 process engine executes a service task that delegates
   note creation to a Camunda External Task handler running inside the application server.

Both paths converge on the same domain service (`NoteService`) and the same PostgreSQL database.
Each persisted note records the name of its creator (`"rest api"` or `"task handler"`),
demonstrating how context can be propagated through the CDI bean graph regardless of the calling
entry point.

The secondary, equally important goal is to serve as a **reference implementation** that shows
how to correctly integrate the Camunda 7 External Task Client inside a Jakarta EE application
server — specifically how to handle CDI bean injection and request-scoped contexts on the
unmanaged threads used by the External Task Client.

### 1.2 Quality Goals

| Priority | Quality Goal | Motivation |
|---|---|---|
| 1 | **Correctness** | Notes created via REST and via BPMN must both be persisted consistently with the right creator name and within a JTA transaction. |
| 2 | **Maintainability** | Business logic (NoteService) must be independent of the calling entry point. Adding new BPMN processes or REST endpoints must not require changes to the service layer. |
| 3 | **Demonstrability** | The integration between Jakarta EE CDI and the Camunda External Task Client must be explicit, well-commented, and easy to follow for developers learning the pattern. |
| 4 | **Operability** | The full system must start with a single `docker compose up --build` command. |

### 1.3 Stakeholders

| Role | Expectation |
|---|---|
| **Jakarta EE developer** | Understands how to run the Camunda External Task Client inside WildFly and how CDI injection works in that context. |
| **Camunda developer** | Understands how to implement and deploy an external task handler as part of a Jakarta EE application. |
| **DevOps engineer** | Understands the Docker Compose setup and container dependencies. |

---

## 2. Architecture Constraints

### 2.1 Technical Constraints

| Constraint | Rationale |
|---|---|
| **Jakarta EE 10** only — no Spring | The system exists to demonstrate pure Jakarta EE patterns. Spring's CDI equivalent (Spring Context) is explicitly out of scope. |
| **WildFly 32** as application server | WildFly is the reference Jakarta EE 10 implementation used in this project. |
| **Camunda 7** (not Camunda 8 / Zeebe) | Camunda 7 uses a relational database and the External Task pattern. Camunda 8 uses a different client API and Zeebe as its engine; those patterns are not addressed here. |
| **Single WAR deployment** | No EAR. The EJB module and the Camunda client module are packaged into `WEB-INF/lib` inside the WAR. This simplifies the build and deployment at the cost of a strict JEE packaging hierarchy. |
| **Java 17** | Minimum JDK version required by WildFly 32 and Camunda External Task Client 7.21. |
| **Docker Compose** for local operation | All external dependencies (PostgreSQL, Camunda engine) are containerised and started by Docker Compose. |

### 2.2 Organisational Constraints

| Constraint | Rationale |
|---|---|
| Maven multi-module build | Modules enforce compile-time dependency boundaries and reflect the logical layering of the application. |
| No authentication or authorisation | Out of scope for this reference implementation. |

---

## 3. System Scope and Context

### 3.1 Business Context

```
                          ┌──────────────────────────────────┐
                          │        Notes Application         │
                          │                                  │
  REST Client ───HTTP────►│  /api/notes  (JAX-RS)            │
                          │                                  │
  Camunda Engine ─tasks──►│  add-note    (External Task)     │──── PostgreSQL
                          │                                  │
  Camunda Modeler ─BPMN──►│  /deployment/create  (at boot)   │
                          │                                  │
                          └──────────────────────────────────┘
```

| Neighbour | Communication | Direction | Protocol |
|---|---|---|---|
| REST Client | Create / read / update / delete notes | Client → System | HTTP/JSON |
| Camunda Engine | Delivers external tasks for processing | Engine → System (long-poll) | HTTP/JSON (REST) |
| Camunda Engine | Receives BPMN process deployment | System → Engine | HTTP/JSON (REST) |
| PostgreSQL | Note persistence | System → DB | JDBC / TCP |

### 3.2 Technical Context

```
  ┌─────────────────────────────────────────────────────────────────┐
  │  Docker network                                                 │
  │                                                                 │
  │  ┌───────────────────┐   JDBC    ┌──────────────────────────┐  │
  │  │   WildFly 32      │──────────►│  PostgreSQL 16           │  │
  │  │   (notes WAR)     │           │  notesdb  │  camundadb   │  │
  │  └────────┬──────────┘           └──────────────────────────┘  │
  │           │  HTTP long-poll                  ▲                  │
  │           │  (engine-rest)                   │ JDBC             │
  │           ▼                                  │                  │
  │  ┌───────────────────┐                       │                  │
  │  │  Camunda 7.21     │───────────────────────┘                  │
  │  │  (Tomcat)         │                                          │
  │  └───────────────────┘                                          │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘

  External:
  REST Client ──HTTP:8080──► WildFly
  Browser     ──HTTP:8090──► Camunda web app
```

| Channel | Protocol | Port |
|---|---|---|
| REST API (WildFly) | HTTP | 8080 |
| WildFly management | HTTP | 9990 |
| Camunda web app + REST | HTTP | 8090 |
| PostgreSQL | TCP/JDBC | 5432 |

---

## 4. Solution Strategy

| Goal | Strategy |
|---|---|
| **Single domain service** for both entry points | `NoteService` is `@ApplicationScoped`. Both `NoteResource` and `AddNoteHandler` inject it. The service is unaware of which caller it serves. |
| **Context propagation** without coupling the service to the caller | A `@RequestScoped` CDI bean (`CreatorContext`) carries the caller identity. The service reads it via a proxy at call time. Each entry point sets the value before invoking the service. |
| **CDI injection in the External Task handler** | `AddNoteHandler` is declared `@Dependent`, making it a full CDI bean that receives its dependencies via constructor injection with `@Inject`. |
| **Request context on an unmanaged thread** | `AddNoteHandler.execute()` uses `RequestContextController.activate()` / `deactivate()` to open and close a synthetic request context around each task execution, making `@RequestScoped` beans usable on Camunda's worker threads. |
| **Bootstrapping the External Task Client inside WildFly** | A `@Singleton @Startup` EJB (`CamundaClientStartup`) starts the client using a `ManagedExecutorService` to respect the EJB threading model. |
| **BPMN process deployment** | The startup EJB deploys the bundled BPMN resource to the Camunda engine via its REST API on every application start (`deploy-changed-only=true` prevents redundant re-deployments). |
| **Containerised operation** | Docker Compose with a healthcheck dependency chain: postgres → camunda → wildfly. |

---

## 5. Building Block View

### 5.1 Level 1 — System Decomposition

```
┌──────────────────────────────────────────────────────────────────┐
│  notes-app (WAR deployment on WildFly)                           │
│                                                                  │
│  ┌─────────────┐  ┌──────────────────────┐  ┌────────────────┐  │
│  │  notes-ejb  │  │ notes-camunda-client  │  │   notes-war    │  │
│  │  (domain)   │  │ (Camunda integration) │  │  (REST layer)  │  │
│  └─────────────┘  └──────────────────────┘  └────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

| Building Block | Responsibility |
|---|---|
| **notes-ejb** | JPA entity `Note`, business service `NoteService`, CDI context bean `CreatorContext`. Encapsulates all domain logic and persistence. Has no knowledge of REST or BPMN. |
| **notes-camunda-client** | `CamundaClientStartup` (bootstraps the External Task Client), `AddNoteHandler` (handles the `add-note` topic), `add-note-process.bpmn`. Bridges the Camunda engine and the domain service. |
| **notes-war** | JAX-RS activator, `NoteResource` (REST endpoints), `NoteRequest` (DTO), `JacksonConfig` (Jackson ObjectMapper customisation). Exposes the domain over HTTP. |

### 5.2 Level 2 — notes-ejb

```
notes-ejb
├── entity/
│   └── Note                  JPA entity (@Entity @Table("notes"))
│                             Fields: id, title, content, creatorName, createdAt
└── service/
    ├── NoteService            @ApplicationScoped — CRUD operations via EntityManager
    │                         Reads CreatorContext to stamp the creator on persist.
    └── CreatorContext         @RequestScoped — mutable holder for the creator name.
                              Injected as a proxy everywhere; resolved at call time.
```

### 5.3 Level 2 — notes-camunda-client

```
notes-camunda-client
├── CamundaClientStartup      @Singleton @Startup EJB
│                             1. Waits for Camunda engine (poll /engine-rest/engine)
│                             2. Deploys add-note-process.bpmn via REST
│                             3. Builds ExternalTaskClient and subscribes AddNoteHandler
│                             Uses ManagedExecutorService for background work.
│
├── AddNoteHandler             @Dependent ExternalTaskHandler
│                             Activates request context → sets CreatorContext →
│                             calls NoteService → completes or fails the task.
│
└── resources/
    ├── add-note-process.bpmn  BPMN 2.0 process definition (see §5.4)
    └── META-INF/ejb-jar.xml   Marker so WildFly scans this JAR for EJB annotations
```

### 5.4 Level 2 — notes-war

```
notes-war
├── JaxRsActivator             @ApplicationPath("/api") — activates JAX-RS
├── NoteResource               @Path("/notes") — GET, POST, PUT, DELETE
│                             Injects CreatorContext, sets "rest api" before POST.
├── NoteRequest                Plain DTO for the POST/PUT request body
└── JacksonConfig              @Provider ContextResolver<ObjectMapper>
                              Registers JavaTimeModule so LocalDateTime serialises
                              as ISO-8601 strings rather than numeric arrays.
```

### 5.5 Level 3 — Key Class Interactions

```
NoteResource                    AddNoteHandler
    │  @Inject                      │  @Inject (constructor)
    ├─► NoteService (proxy)         ├─► NoteService (proxy)
    └─► CreatorContext (proxy)      ├─► CreatorContext (proxy)
              │                     └─► RequestContextController
              │                                │
              │         activate()             │
              │◄──────────────────────────────-┤
              │                                │
              ▼                                ▼
         NoteService  ──@Inject──►  CreatorContext (proxy)
              │                         │ resolves at call time to the
              │                         │ active @RequestScoped instance
              ▼                         ▼
         EntityManager           creatorName: "rest api"
              │                    or "task handler"
              ▼
         PostgreSQL
```

---

## 6. Runtime View

### 6.1 Scenario: Create Note via REST API

```
REST Client          NoteResource        CreatorContext       NoteService         PostgreSQL
    │                     │                   │                   │                   │
    │  POST /api/notes     │                   │                   │                   │
    │─────────────────────►│                   │                   │                   │
    │                      │  servlet container activates          │                   │
    │                      │  @RequestScoped context automatically │                   │
    │                      │──setCreatorName("rest api")──────────►│ (proxy resolves)  │
    │                      │                   │ ◄─ real instance  │                   │
    │                      │──createNote(title, content)──────────►│                   │
    │                      │                   │                   │──getCreatorName()─►│
    │                      │                   │◄──"rest api"──────│                   │
    │                      │                   │                   │──INSERT note──────►│
    │                      │                   │                   │◄──note (with id)───│
    │                      │◄────────────────────────────────────── note               │
    │  201 Created (note)  │                   │                   │                   │
    │◄─────────────────────│                   │                   │                   │
    │                      │  container deactivates @RequestScoped context             │
```

### 6.2 Scenario: Create Note via Camunda External Task

```
Camunda Engine    CamundaClientStartup    AddNoteHandler     CreatorContext    NoteService    PostgreSQL
      │                   │                    │                  │                │               │
      │  [task available] │                    │                  │                │               │
      │◄──long-poll───────│                    │                  │                │               │
      │──lock & return────►                    │                  │                │               │
      │                   │──execute(task)─────►                  │                │               │
      │                   │                    │─activate()──────►│ (open context) │               │
      │                   │                    │─setCreatorName───►                │               │
      │                   │                    │  ("task handler")│                │               │
      │                   │                    │──createNote(title, content)───────►               │
      │                   │                    │                  │◄─proxy resolves─               │
      │                   │                    │                  │─getCreatorName()               │
      │                   │                    │                  │──"task handler"─►              │
      │                   │                    │                  │                 │─INSERT───────►│
      │                   │                    │                  │                 │◄─note─────────│
      │                   │                    │◄─────────────────────────── note  │               │
      │                   │                    │─deactivate()────►│ (destroy ctx)  │               │
      │◄──complete(task)──│◄───────────────────│                  │                │               │
```

### 6.3 Scenario: Application Startup

```
Docker Compose    PostgreSQL    Camunda Engine    WildFly / CamundaClientStartup
      │               │               │                       │
      │─start─────────►               │                       │
      │               │─initialise DB─►                       │
      │               │─run init.sh───►                       │
      │               │  (create camundadb)                   │
      │               │─healthcheck OK►                       │
      │─start─────────────────────────►                       │
      │               │               │─connect to camundadb─►│
      │               │               │─create schema─────────►│
      │               │               │─healthcheck OK─────────►│
      │─start─────────────────────────────────────────────────►│
      │               │               │                        │─@PostConstruct
      │               │               │                        │─submit to ManagedExecutor
      │               │               │◄──GET /engine-rest/engine (poll until 200)
      │               │               │──200 OK────────────────►│
      │               │               │◄──POST /deployment/create (BPMN)
      │               │               │──200 OK────────────────►│
      │               │               │◄──long-poll (subscribe "add-note")
      │               │               │  [client running]       │
```

---

## 7. Deployment View

### 7.1 Container Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  Host machine                                                       │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Docker Compose (bridge network)                              │  │
│  │                                                               │  │
│  │  ┌────────────────────┐   ┌────────────────────────────────┐  │  │
│  │  │  notes-postgres    │   │  notes-camunda                 │  │  │
│  │  │  postgres:16       │   │  camunda/camunda-bpm-          │  │  │
│  │  │                    │   │  platform:7.21.0               │  │  │
│  │  │  notesdb           │   │                                │  │  │
│  │  │  camundadb         │   │  engine-rest  :8080            │  │  │
│  │  │                    │   │  cockpit      :8080/camunda    │  │  │
│  │  │  :5432 (host)      │   │                                │  │  │
│  │  │                    │   │  :8090 (host)                  │  │  │
│  │  │  volume:           │   └────────────────────────────────┘  │  │
│  │  │  postgres-data      │                                       │  │
│  │  └────────────────────┘                                        │  │
│  │                                                                │  │
│  │  ┌────────────────────────────────────────────────────────┐    │  │
│  │  │  notes-wildfly                                         │    │  │
│  │  │  quay.io/wildfly/wildfly:32.0.0.Final-jdk17 (custom)  │    │  │
│  │  │                                                        │    │  │
│  │  │  standalone.xml ── NotesDS datasource (notesdb)        │    │  │
│  │  │  deployments/notes.war                                 │    │  │
│  │  │    WEB-INF/lib/notes-ejb-1.0.0-SNAPSHOT.jar           │    │  │
│  │  │    WEB-INF/lib/notes-camunda-client-1.0.0-SNAPSHOT.jar│    │  │
│  │  │    WEB-INF/lib/camunda-external-task-client-7.21.0.jar│    │  │
│  │  │    WEB-INF/lib/httpclient5-5.3.jar                    │    │  │
│  │  │    ...                                                 │    │  │
│  │  │                                                        │    │  │
│  │  │  :8080 (host) — application                           │    │  │
│  │  │  :9990 (host) — management                            │    │  │
│  │  └────────────────────────────────────────────────────────┘    │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.2 Startup Dependency Order

```
postgres ──(healthy)──► camunda ──(healthy)──► wildfly
```

Healthchecks:
- **postgres**: `pg_isready -U notes -d notesdb && pg_isready -U notes -d camundadb`
- **camunda**: `curl -sf http://localhost:8080/engine-rest/engine`
- **wildfly**: no healthcheck (depends on camunda being healthy before it starts)

### 7.3 WildFly Image Build (2-stage Dockerfile)

```
Stage 1 (builder):
  FROM quay.io/wildfly/wildfly:32.0.0.Final-jdk17
  curl → postgresql-42.7.3.jar
  jboss-cli.sh --file=configure-wildfly.cli
    └─ module add org.postgresql
    └─ datasource add NotesDS → jdbc:postgresql://postgres:5432/notesdb

Stage 2 (runtime):
  FROM quay.io/wildfly/wildfly:32.0.0.Final-jdk17
  COPY --from=builder /opt/jboss/wildfly  (pre-configured)
  COPY notes-war/target/notes.war → deployments/
```

---

## 8. Crosscutting Concepts

### 8.1 CDI Dependency Injection

The application uses Jakarta CDI (Weld, bundled with WildFly) throughout. Two injection styles
are used:

**Field injection** — used in `NoteResource` and `NoteService` where the class is a CDI
normal-scoped bean and the injected dependency is long-lived:
```java
@Inject
private NoteService noteService;
```

**Constructor injection** — used in `AddNoteHandler` where the class is `@Dependent` (not
proxied) and immutability of final fields is desirable:
```java
@Inject
public AddNoteHandler(NoteService noteService,
                      CreatorContext creatorContext,
                      RequestContextController requestContextController) { ... }
```

### 8.2 CDI Scopes and Proxy Resolution

| Bean | Scope | Proxy injected? | Context always active? |
|---|---|---|---|
| `NoteService` | `@ApplicationScoped` | Yes | Yes — application lifetime |
| `CreatorContext` | `@RequestScoped` | Yes | Only on threads with an active request context |
| `AddNoteHandler` | `@Dependent` | No | N/A — not proxied |
| `CamundaClientStartup` | EJB `@Singleton` | N/A (EJB) | Yes |

When CDI injects a `@RequestScoped` bean, what is actually injected is a **thread-local proxy**.
The proxy delegates every method call to the real bean instance that lives in the currently active
request context on the calling thread. If no context is active, a `ContextNotActiveException` is
thrown at the moment of the first method call — not at injection time.

### 8.3 Request Context on Unmanaged Threads

Camunda's External Task Client maintains its own internal thread pool. These threads are not
managed by the Jakarta EE container and have no request context associated with them.

The solution is `jakarta.enterprise.context.control.RequestContextController`, a portable CDI
2.0 built-in bean that allows application code to manually manage the request context lifecycle:

```java
requestContextController.activate();   // creates a new @RequestScoped context on this thread
try {
    // @RequestScoped proxies resolve correctly here
} finally {
    requestContextController.deactivate(); // destroys all @RequestScoped instances
}
```

This pattern is used in `AddNoteHandler.execute()` and is equivalent to what the servlet
container does automatically for every HTTP request.

### 8.4 Transaction Management (JTA)

All database operations run under **JTA** (Java Transaction API), managed by WildFly's Narayana
transaction manager. `NoteService` methods that write to the database are annotated
`@Transactional` (from `jakarta.transaction`). WildFly begins a transaction before the method
and commits (or rolls back on exception) after it.

The JTA datasource `java:/jdbc/NotesDS` is configured in WildFly's `standalone.xml` via the CLI
script at image build time.

### 8.5 JSON Serialisation

RESTEasy (WildFly's JAX-RS implementation) uses Jackson 2.15 for JSON. By default, Jackson does
not support `java.time` types such as `LocalDateTime`. A `@Provider`-annotated
`ContextResolver<ObjectMapper>` registers `JavaTimeModule` and disables timestamp serialisation,
so dates are rendered as ISO-8601 strings (e.g. `"2026-03-20T08:52:21"`).

### 8.6 Error Handling in External Task Handlers

If `AddNoteHandler.execute()` throws an exception, it calls
`ExternalTaskService.handleFailure()` with `0` retries. Camunda then creates an **incident**
on the process instance, which is visible in the Camunda Cockpit. This prevents silent task
loss and gives operators visibility into failures.

### 8.7 BPMN Process Deployment Strategy

The BPMN file is bundled as a classpath resource inside `notes-camunda-client.jar` (and
therefore inside the WAR). At application startup, `CamundaClientStartup` deploys it to
Camunda's REST API with `deploy-changed-only=true`. This means:

- On first start: the process is created.
- On subsequent starts with an unchanged BPMN: the deployment is skipped (same hash).
- On redeploy after a BPMN change: a new deployment version is created; running instances
  continue on the old version; new instances use the new version.

---

## 9. Architecture Decisions

### ADR-001: External Task Client Runs Inside WildFly

**Context:** The Camunda External Task Client is a standalone Java library. It can run as a
separate JVM process, a standalone Spring Boot application, or embedded inside an existing
application server.

**Decision:** Embed the client inside WildFly as part of the notes WAR deployment.

**Rationale:**
- The handler needs to call `NoteService`, which is a CDI bean managing JPA and JTA. Running
  the client in a separate JVM would require the handler to call `NoteService` over the network
  (e.g., via the REST API), introducing an extra network hop and losing transactional guarantees.
- Embedding the client inside WildFly allows the handler to call `NoteService` in-process as a
  regular CDI method call, within the same JTA transaction.

**Consequences:**
- The External Task Client's dependency tree (Apache HttpClient 5, Camunda commons) is bundled
  in `WEB-INF/lib`. Jackson and SLF4J are excluded from the bundled dependencies and provided
  by WildFly's module system.
- A `@Singleton @Startup` EJB is required to bootstrap the client, because CDI alone has no
  portable equivalent of `@Startup` in Jakarta EE 10.

---

### ADR-002: `@Dependent` Scope for AddNoteHandler

**Context:** `AddNoteHandler` is a CDI-managed `ExternalTaskHandler`. It needs to be injected
into `CamundaClientStartup` and have its own dependencies injected.

**Decision:** Annotate `AddNoteHandler` with `@Dependent`.

**Rationale:**
- `@Dependent` beans are not proxied. This allows constructor injection with truly `final`
  fields and avoids the need for a no-arg constructor required by proxied (normal-scoped) beans.
- The handler is stateless and has no independent lifecycle requirements. Tying its lifetime
  to the owning `@Singleton` EJB (i.e., the application lifetime) is semantically correct.
- `@ApplicationScoped` would work but requires a `protected` no-arg constructor alongside the
  `@Inject` constructor, which is an unintuitive pattern.

**Consequences:**
- One handler instance exists per application lifetime, created when `CamundaClientStartup`
  is instantiated. This is correct behaviour for a stateless, thread-safe delegate.

---

### ADR-003: `RequestContextController` for `@RequestScoped` Beans in the Handler

**Context:** `CreatorContext` is `@RequestScoped`. Camunda's worker threads have no active
request context. Accessing a `@RequestScoped` proxy on such a thread throws
`ContextNotActiveException`.

**Decision:** Use `jakarta.enterprise.context.control.RequestContextController` to open a
synthetic request context at the start of each `execute()` invocation and close it in a
`finally` block.

**Alternatives considered:**

| Alternative | Why rejected |
|---|---|
| Change `CreatorContext` to `@ApplicationScoped` | Would make it a shared singleton; concurrent task executions would race on `setCreatorName()`. |
| Pass `creatorName` as a method parameter to `NoteService` | Breaks the uniform interface: the REST path would also need to pass the parameter, coupling `NoteResource` to an internal implementation detail. |
| Use `@ApplicationScoped` `CreatorContext` with a `ThreadLocal` internally | Functional, but obscures the CDI semantics. `@RequestScoped` communicates the intent precisely: one value per logical unit of work. |

**Consequences:**
- Each task execution is isolated: the `@RequestScoped` instance created by `activate()` is
  independent of all other concurrent task executions.
- `deactivate()` in `finally` guarantees the synthetic context is always closed, preventing
  memory leaks even when the handler throws.

---

### ADR-004: Single WAR Deployment (no EAR)

**Context:** The project has three Maven modules. A conventional JEE multi-module project often
produces an EAR that contains an EJB JAR and a WAR.

**Decision:** Package everything into a single WAR. The EJB JAR and the Camunda client JAR are
placed in `WEB-INF/lib`.

**Rationale:**
- WildFly supports EJBs inside `WEB-INF/lib` JARs of a WAR deployment (triggered by
  `META-INF/ejb-jar.xml` inside the JAR).
- A single WAR is simpler to build, deploy, and understand for a reference implementation.
- An EAR would be appropriate when multiple WARs need to share EJB components, which is not
  the case here.

**Consequences:**
- An `META-INF/ejb-jar.xml` marker file is required in `notes-camunda-client.jar` to instruct
  WildFly's EJB subsystem to scan that JAR for `@Singleton @Startup`.

---

### ADR-005: BPMN Deployed Programmatically at Startup, Not Mounted as a Volume

**Context:** The BPMN process definition needs to reach the Camunda engine. Options include
mounting it as a file into the Camunda container or deploying it programmatically.

**Decision:** Bundle the BPMN as a classpath resource in `notes-camunda-client` and deploy it
to Camunda's REST API at startup.

**Rationale:**
- Keeping the BPMN inside the application JAR ensures it is version-controlled together with
  the handler that implements it. A mismatch between deployed code and deployed BPMN is
  impossible.
- The `deploy-changed-only=true` flag prevents redundant re-deployments on every restart.

**Consequences:**
- `CamundaClientStartup` must wait for the Camunda engine to be ready before deploying, which
  is handled by a polling loop.

---

## 10. Quality Requirements

### 10.1 Quality Tree

```
Quality
├── Correctness
│   ├── Notes created via REST always have creatorName = "rest api"
│   ├── Notes created via BPMN always have creatorName = "task handler"
│   └── All DB writes are within a JTA transaction (rolled back on failure)
│
├── Maintainability
│   ├── NoteService has no import from notes-war or notes-camunda-client
│   └── Adding a new BPMN process requires only a new handler + BPMN file
│
├── Demonstrability
│   ├── CDI injection pattern in the handler is explicit and documented
│   └── RequestContextController usage is self-contained in AddNoteHandler
│
└── Operability
    ├── System starts with docker compose up --build
    └── Schema is created automatically on first start
```

### 10.2 Quality Scenarios

| ID | Quality | Stimulus | Response |
|---|---|---|---|
| QS-1 | Correctness | POST to /api/notes | Note persisted with `creatorName="rest api"` and HTTP 201 returned. |
| QS-2 | Correctness | Camunda process instance started with title/content variables | Note persisted with `creatorName="task handler"`; task completed with `noteId` output variable. |
| QS-3 | Correctness | NoteService throws during task execution | `handleFailure()` called with 0 retries; incident created in Camunda; no partial write in database. |
| QS-4 | Correctness | Two concurrent external task executions | Each execution has its own `@RequestScoped` `CreatorContext` instance; no data race. |
| QS-5 | Operability | `docker compose up --build` on a machine with Docker and Maven installed | All three containers start; REST API and Camunda Cockpit are reachable within ~90 seconds. |
| QS-6 | Maintainability | A developer adds a second BPMN process topic | Only a new `ExternalTaskHandler` class and BPMN file are required; `NoteService` and `NoteResource` are unchanged. |

---

## 11. Risks and Technical Debt

### Risk 1 — Schema creation strategy (`create`, not `drop-and-create`)

**Description:** `persistence.xml` uses `jakarta.persistence.schema-generation.database.action=create`.
This creates tables on first deployment but does not apply subsequent schema changes (e.g. a
new column) to an existing database.

**Impact:** After adding the `creator_name` column to `Note`, an existing `notes` table without
that column will cause runtime errors. The developer must run `docker compose down -v` to
recreate the volume.

**Mitigation:** For production use, replace schema generation with a migration tool such as
Flyway or Liquibase.

---

### Risk 2 — Jackson version coupling between WildFly and Camunda client

**Description:** Jackson is excluded from the Camunda client's bundled dependencies and
provided by WildFly's module system (currently 2.15.4). If WildFly upgrades Jackson to a
version with a breaking API change, the Camunda client may fail at runtime.

**Impact:** Low in practice — Jackson 2.x maintains strong backward compatibility — but the
coupling is implicit and hard to detect at compile time.

**Mitigation:** Pin the Jackson version in WildFly's module system, or switch to bundling
Jackson inside the WAR with an appropriate `jboss-deployment-structure.xml`.

---

### Risk 3 — No authentication or authorisation

**Description:** The REST API and the Camunda Cockpit are completely unauthenticated. Any
client with network access can create, read, update, or delete notes and trigger BPMN
processes.

**Impact:** Unacceptable for any non-local deployment.

**Mitigation:** Add Jakarta Security (e.g., JWT via MicroProfile JWT or WildFly Elytron) to
the REST API. Configure Camunda Cockpit authentication (enabled by default in production images).

---

### Risk 4 — Camunda deployment retry does not fail the application

**Description:** If the BPMN deployment to Camunda fails (e.g. network error, Camunda schema
not ready), `CamundaClientStartup` logs a warning and continues. The External Task Client
starts anyway, polling for tasks that will never be dispatched because the process definition
does not exist.

**Impact:** Silent misconfiguration — the application appears healthy but cannot process
Camunda tasks.

**Mitigation:** Add a health indicator that checks whether the process definition is deployed
and expose it via WildFly's management API or a dedicated health endpoint.

---

## 12. Glossary

| Term | Definition |
|---|---|
| **arc42** | A pragmatic, lightweight template for software architecture documentation. |
| **BPMN** | Business Process Model and Notation — a graphical standard for specifying business processes. |
| **CDI** | Contexts and Dependency Injection — the Jakarta EE standard for type-safe dependency injection and contextual lifecycle management. |
| **`@Dependent`** | CDI pseudo-scope. A `@Dependent` bean is not proxied and shares the lifecycle of the bean that injects it. |
| **`@RequestScoped`** | CDI normal scope. One bean instance per active request context. The container creates the context at the start of an HTTP request and destroys it at the end. |
| **`@ApplicationScoped`** | CDI normal scope. One bean instance per application lifetime. |
| **External Task** | A Camunda 7 pattern in which a BPMN service task is not executed by the engine itself but is exposed as a work item that an external worker polls, locks, and completes via the REST API. |
| **External Task Client** | The `org.camunda.bpm:camunda-external-task-client` Java library that implements the long-polling worker loop. |
| **`ExternalTaskHandler`** | Functional interface from the External Task Client library. `execute(ExternalTask, ExternalTaskService)` is called when a task is locked. |
| **JPA** | Jakarta Persistence API — the standard ORM API used to map `Note` to a database table. |
| **JTA** | Jakarta Transaction API — the standard for distributed transactions in Jakarta EE. `@Transactional` in `NoteService` uses JTA managed by WildFly's Narayana transaction manager. |
| **Long-polling** | The technique used by the External Task Client: it sends a request to Camunda and the engine holds the connection open until a task becomes available (up to `asyncResponseTimeout` ms). |
| **`ManagedExecutorService`** | Jakarta EE Concurrency API. A thread pool managed by the application server, suitable for use inside EJBs where spawning unmanaged threads is forbidden. |
| **`RequestContextController`** | A portable CDI 2.0 built-in bean (`jakarta.enterprise.context.control`) that allows application code to manually activate and deactivate a request context on any thread. |
| **RESTEasy** | The JAX-RS implementation bundled with WildFly. |
| **Weld** | The CDI reference implementation, bundled with WildFly. |
| **WildFly** | Open-source Jakarta EE 10 application server, developed by Red Hat. Used as the runtime for this application. |