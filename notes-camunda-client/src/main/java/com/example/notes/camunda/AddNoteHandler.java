package com.example.notes.camunda;

import com.example.notes.entity.Note;
import com.example.notes.service.NoteService;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles the "add-note" external task topic.
 *
 * Expected process variables:
 *   - title   (String, required) – note title
 *   - content (String, optional) – note body
 *
 * Output process variable:
 *   - noteId  (Long) – id of the persisted note
 */
public class AddNoteHandler implements ExternalTaskHandler {

    private static final Logger logger = Logger.getLogger(AddNoteHandler.class.getName());

    private final NoteService noteService;

    public AddNoteHandler(NoteService noteService) {
        this.noteService = noteService;
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String title   = externalTask.getVariable("title");
        String content = externalTask.getVariable("content");

        logger.info("Processing add-note task " + externalTask.getId()
                + " | title=" + title);

        try {
            Note note = noteService.createNote(title, content);

            externalTaskService.complete(externalTask, Map.of("noteId", note.getId()));

            logger.info("Note created with id=" + note.getId()
                    + " for task " + externalTask.getId());
        } catch (Exception e) {
            logger.severe("Failed to create note for task " + externalTask.getId()
                    + ": " + e.getMessage());
            // 0 retries remaining → incident is raised in Camunda
            externalTaskService.handleFailure(
                    externalTask,
                    "Note creation failed",
                    e.getMessage(),
                    0,
                    0L
            );
        }
    }
}