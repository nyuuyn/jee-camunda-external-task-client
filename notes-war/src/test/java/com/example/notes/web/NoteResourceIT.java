package com.example.notes.web;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for {@link NoteResource} running against the live Docker Compose stack.
 *
 * Prerequisites: {@code docker compose up --build} must be running before executing these tests.
 *
 * Run unit tests only:  mvn test
 * Run everything:       mvn verify
 */
class NoteResourceIT {

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = 8080;
        RestAssured.basePath = "/notes/api";
    }

    // ── GET /notes ────────────────────────────────────────────────────────────

    @Test
    void getAll_returns200_withJsonArray() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/notes")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("$", instanceOf(java.util.List.class));
    }

    // ── POST /notes ───────────────────────────────────────────────────────────

    @Test
    void createNote_withValidTitle_returns201_withRestApiCreatorName() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"title": "IT Test Note", "content": "Created by integration test"}
                    """)
        .when()
            .post("/notes")
        .then()
            .statusCode(201)
            .body("id",          notNullValue())
            .body("title",       equalTo("IT Test Note"))
            .body("content",     equalTo("Created by integration test"))
            .body("creatorName", equalTo("rest api"))
            .body("createdAt",   notNullValue());
    }

    @Test
    void createNote_withBlankTitle_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"title": "", "content": "no title"}""")
        .when()
            .post("/notes")
        .then()
            .statusCode(400);
    }

    @Test
    void createNote_withMissingTitle_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"content": "no title field"}""")
        .when()
            .post("/notes")
        .then()
            .statusCode(400);
    }

    // ── GET /notes/{id} ───────────────────────────────────────────────────────

    @Test
    void getById_forExistingNote_returns200() {
        // Create a note first, then fetch it by the returned id
        int id = given()
            .contentType(ContentType.JSON)
            .body("""{"title": "Fetch Me", "content": ""}""")
        .when()
            .post("/notes")
        .then()
            .statusCode(201)
            .extract().path("id");

        given()
            .accept(ContentType.JSON)
        .when()
            .get("/notes/" + id)
        .then()
            .statusCode(200)
            .body("id",    equalTo(id))
            .body("title", equalTo("Fetch Me"));
    }

    @Test
    void getById_forNonExistentNote_returns404() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/notes/999999999")
        .then()
            .statusCode(404);
    }

    // ── DELETE /notes/{id} ────────────────────────────────────────────────────

    @Test
    void deleteNote_returns204_andNoteIsGone() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""{"title": "Delete Me", "content": ""}""")
        .when()
            .post("/notes")
        .then()
            .statusCode(201)
            .extract().path("id");

        given()
        .when()
            .delete("/notes/" + id)
        .then()
            .statusCode(204);

        given()
            .accept(ContentType.JSON)
        .when()
            .get("/notes/" + id)
        .then()
            .statusCode(404);
    }

    @Test
    void deleteNote_forNonExistentNote_returns404() {
        given()
        .when()
            .delete("/notes/999999999")
        .then()
            .statusCode(404);
    }

    // ── PUT /notes/{id} ───────────────────────────────────────────────────────

    @Test
    void updateNote_returns200_withUpdatedFields() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""{"title": "Original", "content": "original content"}""")
        .when()
            .post("/notes")
        .then()
            .statusCode(201)
            .extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""{"title": "Updated", "content": "updated content"}""")
        .when()
            .put("/notes/" + id)
        .then()
            .statusCode(200)
            .body("title",   equalTo("Updated"))
            .body("content", equalTo("updated content"));
    }
}