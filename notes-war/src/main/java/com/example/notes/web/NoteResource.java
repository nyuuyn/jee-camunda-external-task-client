package com.example.notes.web;

import com.example.notes.entity.Note;
import com.example.notes.service.CreatorContext;
import com.example.notes.service.NoteService;
import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NoteResource {

    @Inject
    private NoteService noteService;

    /**
     * The servlet container activates the @RequestScoped context for every
     * incoming HTTP request, so this injection is always safe here.
     */
    @Inject
    private CreatorContext creatorContext;

    /**
     * Injected by the EE container from java:global/creator/restApi,
     * configured as a naming binding in WildFly's standalone.xml
     * (see docker/configure-wildfly.cli).
     *
     * @Resource is processed independently of CDI: the container sets this
     * field after the bean is constructed. It cannot be final, but it is
     * effectively constant for the lifetime of the application.
     */
    @Resource(lookup = "java:global/creator/restApi")
    private String restApiCreatorName;

    @GET
    public List<Note> getAll() {
        return noteService.findAll();
    }

    @GET
    @Path("/{id}")
    public Note getById(@PathParam("id") Long id) {
        Note note = noteService.findById(id);
        if (note == null) {
            throw new NotFoundException("Note not found: " + id);
        }
        return note;
    }

    @POST
    public Response create(NoteRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"error\":\"title is required\"}")
                           .build();
        }
        creatorContext.setCreatorName(restApiCreatorName);
        Note note = noteService.createNote(request.getTitle(), request.getContent());
        return Response.status(Response.Status.CREATED).entity(note).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") Long id, NoteRequest request) {
        Note note = noteService.updateNote(id, request.getTitle(), request.getContent());
        if (note == null) {
            throw new NotFoundException("Note not found: " + id);
        }
        return Response.ok(note).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = noteService.deleteNote(id);
        if (!deleted) {
            throw new NotFoundException("Note not found: " + id);
        }
        return Response.noContent().build();
    }
}