package com.example.notes.camunda;

import com.example.notes.entity.Note;
import com.example.notes.service.CreatorContext;
import com.example.notes.service.NoteService;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.inject.Inject;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles the "add-note" external task topic.
 *
 * Scoping rationale:
 *   @Dependent – not proxied, so no no-arg constructor is needed, and final
 *   fields are possible. The instance is owned by (and lives as long as) the
 *   injecting @Singleton EJB CamundaClientStartup.
 *
 * Request context:
 *   Camunda's worker threads are unmanaged – the Jakarta EE container has not
 *   associated a request context with them. @RequestScoped beans (like
 *   CreatorContext) are always injected as proxies; the proxy resolves to the
 *   real instance by looking up the *active* request context on the calling
 *   thread at method-invocation time.
 *
 *   RequestContextController (CDI 2.0 / Jakarta EE 9+) lets us open a synthetic
 *   request context on any thread. We activate it at the start of execute(),
 *   populate CreatorContext, then deactivate it in the finally block – mirroring
 *   exactly what the servlet container does for every HTTP request.
 *
 * Expected process variables:
 *   - title   (String, required) – note title
 *   - content (String, optional) – note body
 *
 * Output process variable:
 *   - noteId  (Long) – id of the persisted note
 */
@Dependent
public class AddNoteHandler implements ExternalTaskHandler {

    private static final Logger logger = Logger.getLogger(AddNoteHandler.class.getName());

    private final NoteService noteService;
    private final CreatorContext creatorContext;
    private final RequestContextController requestContextController;

    /**
     * Resolved via the lookup chain:
     *   @Resource(name="creator/taskHandler")
     *     → java:comp/env/creator/taskHandler  (env-entry in web.xml)
     *       → java:global/creator/taskHandler  (WildFly naming binding in standalone.xml)
     *
     * The <lookup-name> in web.xml makes this entry an alias; no value is
     * hardcoded in the WAR.  @Resource is processed by the EE container after
     * construction, so the field cannot be final.
     */
    @Resource(name = "creator/taskHandler")
    private String taskHandlerCreatorName;

    @Inject
    public AddNoteHandler(NoteService noteService,
                          CreatorContext creatorContext,
                          RequestContextController requestContextController) {
        this.noteService              = noteService;
        this.creatorContext           = creatorContext;
        this.requestContextController = requestContextController;
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        // Open a synthetic request context so the @RequestScoped CreatorContext
        // proxy can resolve to a real instance on this unmanaged thread.
        requestContextController.activate();
        try {
            creatorContext.setCreatorName(taskHandlerCreatorName);

            String title   = externalTask.getVariable("title");
            String content = externalTask.getVariable("content");

            logger.info("Processing add-note task " + externalTask.getId()
                    + " | title=" + title);

            Note note = noteService.createNote(title, content);

            externalTaskService.complete(externalTask, Map.of("noteId", note.getId()));

            logger.info("Note created with id=" + note.getId()
                    + ", creator='" + note.getCreatorName()
                    + "' for task " + externalTask.getId());

        } catch (Exception e) {
            logger.severe("Failed to create note for task " + externalTask.getId()
                    + ": " + e.getMessage());
            externalTaskService.handleFailure(
                    externalTask,
                    "Note creation failed",
                    e.getMessage(),
                    0,
                    0L
            );
        } finally {
            // Always deactivate – destroys the @RequestScoped instance for this execution.
            requestContextController.deactivate();
        }
    }
}