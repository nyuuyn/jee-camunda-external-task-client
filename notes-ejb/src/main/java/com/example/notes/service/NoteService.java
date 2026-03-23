package com.example.notes.service;

import com.example.notes.entity.Note;
import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.List;

@ApplicationScoped
public class NoteService {

    @PersistenceContext(unitName = "notesPU")
    private EntityManager em;

    /**
     * CDI injects a proxy here. The proxy resolves to the actual @RequestScoped
     * instance at method-call time, so it works from both the servlet request
     * context (JAX-RS) and the synthetic request context opened by AddNoteHandler
     * via RequestContextController.
     */
    @Inject
    private CreatorContext creatorContext;

    /**
     * Valid creator names, resolved via the EAR-level resource-refs in application.xml
     * (accessible from every component in the EAR, including this EJB JAR).
     *
     * Lookup chain:
     *   @Resource(lookup="java:app/creator/restApi")
     *     -> java:app/creator/restApi       (EAR scope, application.xml)
     *       -> java:global/creator/restApi  (WildFly standalone.xml)
     */
    @Resource(lookup = "java:app/creator/restApi")
    private String restApiCreatorName;

    @Resource(lookup = "java:app/creator/taskHandler")
    private String taskHandlerCreatorName;

    @Transactional
    public Note createNote(String title, String content) {
        String creatorName = creatorContext.getCreatorName();
        if (!Set.of(restApiCreatorName, taskHandlerCreatorName).contains(creatorName)) {
            throw new IllegalArgumentException(
                    "Invalid creator name: '" + creatorName + "'");
        }
        Note note = new Note();
        note.setTitle(title);
        note.setContent(content);
        note.setCreatorName(creatorName);
        em.persist(note);
        return note;
    }

    public List<Note> findAll() {
        return em.createQuery("SELECT n FROM Note n ORDER BY n.createdAt DESC", Note.class)
                 .getResultList();
    }

    public Note findById(Long id) {
        return em.find(Note.class, id);
    }

    @Transactional
    public Note updateNote(Long id, String title, String content) {
        Note note = em.find(Note.class, id);
        if (note == null) {
            return null;
        }
        note.setTitle(title);
        note.setContent(content);
        return note;
    }

    @Transactional
    public boolean deleteNote(Long id) {
        Note note = em.find(Note.class, id);
        if (note == null) {
            return false;
        }
        em.remove(note);
        return true;
    }
}