package com.example.notes.service;

import com.example.notes.entity.Note;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NoteService#createNote(String, String)}.
 *
 * The class is constructed directly (no CDI container). @Resource and @Inject
 * fields are injected by Mockito. The two @Resource String fields are set via
 * reflection in @BeforeEach because @InjectMocks only injects mock/spy instances.
 * @Transactional is a no-op without a JTA provider, which is intentional —
 * these tests focus exclusively on the creator-name validation logic.
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

    @Mock EntityManager em;
    @Mock CreatorContext creatorContext;

    @InjectMocks NoteService svc;

    @BeforeEach
    void setUp() throws Exception {
        setField(svc, "restApiCreatorName",     "rest api");
        setField(svc, "taskHandlerCreatorName", "task handler");
    }

    // ── Happy paths ───────────────────────────────────────────────────────────

    @Test
    void createNote_withRestApiCreator_persistsAndReturnsNote() {
        when(creatorContext.getCreatorName()).thenReturn("rest api");

        Note result = svc.createNote("My title", "Some content");

        verify(em).persist(any(Note.class));
        assertEquals("rest api",     result.getCreatorName());
        assertEquals("My title",     result.getTitle());
        assertEquals("Some content", result.getContent());
    }

    @Test
    void createNote_withTaskHandlerCreator_persistsAndReturnsNote() {
        when(creatorContext.getCreatorName()).thenReturn("task handler");

        Note result = svc.createNote("Title", "Body");

        verify(em).persist(any(Note.class));
        assertEquals("task handler", result.getCreatorName());
    }

    // ── Validation failures ───────────────────────────────────────────────────

    @Test
    void createNote_withUnknownCreatorName_throwsIAE_andDoesNotPersist() {
        when(creatorContext.getCreatorName()).thenReturn("unknown-caller");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.createNote("Title", "Body"));

        assertTrue(ex.getMessage().contains("unknown-caller"),
                "exception message should include the rejected name");
        verifyNoInteractions(em);
    }

    @Test
    void createNote_withEmptyCreatorName_throwsIAE_andDoesNotPersist() {
        when(creatorContext.getCreatorName()).thenReturn("");

        assertThrows(IllegalArgumentException.class,
                () -> svc.createNote("Title", "Body"));

        verifyNoInteractions(em);
    }

    @Test
    void createNote_withNullCreatorName_throwsException_andDoesNotPersist() {
        // Set.of(...).contains(null) throws NullPointerException on immutable sets.
        when(creatorContext.getCreatorName()).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> svc.createNote("Title", "Body"));

        verifyNoInteractions(em);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}