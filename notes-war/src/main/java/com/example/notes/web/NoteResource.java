package com.example.notes.web;

import com.example.notes.entity.Note;
import com.example.notes.service.NoteService;
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