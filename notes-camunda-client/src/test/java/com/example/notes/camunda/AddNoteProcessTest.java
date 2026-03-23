package com.example.notes.camunda;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.junit5.ProcessEngineExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Process-level tests for add-note-process.bpmn.
 *
 * Uses an in-memory Camunda engine (H2) started by {@link ProcessEngineExtension}.
 * These tests verify the BPMN structure and the variable contract between the
 * process and the external task handler — without involving WildFly, CDI, or JPA.
 *
 * Engine services are obtained directly from the extension via engine.get*Service()
 * to avoid the field-injection limitation of the static @RegisterExtension form.
 *
 * Each test method is annotated with @Deployment so the process is deployed fresh
 * and rolled back after the test, keeping tests independent.
 */
class AddNoteProcessTest {

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .build(); // uses camunda.cfg.xml from src/test/resources (H2 in-memory)

    // ── Topic and process structure ───────────────────────────────────────────

    @Test
    @Deployment(resources = "add-note-process.bpmn")
    void process_createsOneExternalTask_withTopicAddNote() {
        engine.getRuntimeService().startProcessInstanceByKey("add-note-process",
                Map.of("title", "Hello", "content", "World"));

        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(10, "test-worker")
                .topic("add-note", 10_000)
                .execute();

        assertEquals(1, tasks.size());
        assertEquals("add-note", tasks.get(0).getTopicName());
    }

    // ── Variable passing ──────────────────────────────────────────────────────

    @Test
    @Deployment(resources = "add-note-process.bpmn")
    void process_passesTitle_andContent_toExternalTask() {
        engine.getRuntimeService().startProcessInstanceByKey("add-note-process",
                Map.of("title", "My Note", "content", "Note body"));

        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(1, "test-worker")
                .topic("add-note", 10_000)
                .variables("title", "content")
                .execute();

        assertEquals(1, tasks.size());
        LockedExternalTask task = tasks.get(0);
        // variables() was specified in fetchAndLock; getVariables() returns Map<String, Object>
        assertEquals("My Note",   task.getVariables().get("title"));
        assertEquals("Note body", task.getVariables().get("content"));
    }

    // ── Happy-path completion ─────────────────────────────────────────────────

    @Test
    @Deployment(resources = "add-note-process.bpmn")
    void process_endsAfterExternalTaskCompletion() {
        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("add-note-process",
                Map.of("title", "Hello", "content", "World"));

        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(1, "test-worker")
                .topic("add-note", 10_000)
                .execute();

        engine.getExternalTaskService().complete(tasks.get(0).getId(), "test-worker",
                Map.of("noteId", 42L));

        assertNull(
                engine.getRuntimeService().createProcessInstanceQuery()
                        .processInstanceId(pi.getId())
                        .singleResult(),
                "process instance should no longer be active after task completion");
    }

    @Test
    @Deployment(resources = "add-note-process.bpmn")
    void process_storesNoteId_asOutputVariable() {
        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("add-note-process",
                Map.of("title", "Hello", "content", "World"));

        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(1, "test-worker")
                .topic("add-note", 10_000)
                .execute();

        engine.getExternalTaskService().complete(tasks.get(0).getId(), "test-worker",
                Map.of("noteId", 99L));

        Long stored = (Long) engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(pi.getId())
                .variableName("noteId")
                .singleResult()
                .getValue();

        assertEquals(99L, stored);
    }

    // ── Failure path ──────────────────────────────────────────────────────────

    @Test
    @Deployment(resources = "add-note-process.bpmn")
    void process_createsIncident_whenExternalTaskFails() {
        ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("add-note-process",
                Map.of("title", "Hello", "content", "World"));

        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(1, "test-worker")
                .topic("add-note", 10_000)
                .execute();

        // Simulate what AddNoteHandler does on exception: 0 retries → immediate incident
        engine.getExternalTaskService().handleFailure(
                tasks.get(0).getId(), "test-worker",
                "Note creation failed", "Simulated error", 0, 0L);

        long incidents = engine.getRuntimeService().createIncidentQuery()
                .processInstanceId(pi.getId())
                .count();

        assertEquals(1, incidents, "one incident should be created after handleFailure with 0 retries");
    }
}
