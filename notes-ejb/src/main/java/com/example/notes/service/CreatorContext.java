package com.example.notes.service;

import jakarta.enterprise.context.RequestScoped;

/**
 * Request-scoped CDI bean that carries the name of whoever triggered the
 * current note-creation request.
 *
 * Lifecycle:
 *  - In a JAX-RS request the servlet container activates the request context
 *    automatically; NoteResource populates this bean before calling NoteService.
 *  - In an External Task handler there is no servlet request, so AddNoteHandler
 *    manually activates a synthetic request context via RequestContextController,
 *    populates this bean, then deactivates the context again when done.
 *
 * NoteService reads creatorName from this bean when persisting a Note, without
 * needing to know which entry point triggered the operation.
 */
@RequestScoped
public class CreatorContext {

    private String creatorName;

    public String getCreatorName() {
        return creatorName;
    }

    public void setCreatorName(String creatorName) {
        this.creatorName = creatorName;
    }
}