package com.example.notes.service;

import com.example.notes.entity.Note;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

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

    @Transactional
    public Note createNote(String title, String content) {
        Note note = new Note();
        note.setTitle(title);
        note.setContent(content);
        note.setCreatorName(creatorContext.getCreatorName());
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