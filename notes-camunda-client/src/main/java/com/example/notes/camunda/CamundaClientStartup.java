package com.example.notes.camunda;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.enterprise.concurrent.ContextService;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.inject.Inject;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTaskHandler;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Singleton EJB that starts on application deployment.
 *
 * Responsibilities:
 *  1. Wait until the Camunda engine REST API is reachable.
 *  2. Deploy the BPMN process if not already deployed.
 *  3. Subscribe an External Task Client to the "add-note" topic.
 */
@Singleton
@Startup
public class CamundaClientStartup {

    private static final Logger logger = Logger.getLogger(CamundaClientStartup.class.getName());

    static final String CAMUNDA_BASE_URL = "http://camunda:8080/engine-rest";
    private static final String BOUNDARY  = "----NotesAppBoundary";

    /**
     * CDI creates AddNoteHandler (@Dependent) and injects NoteService,
     * CreatorContext and RequestContextController into it automatically.
     */
    @Inject
    private AddNoteHandler addNoteHandler;

    @Resource(lookup = "java:comp/DefaultContextService")
    private ContextService contextService;

    /** Jakarta EE Concurrency – avoids spawning unmanaged threads inside an EJB. */
    @Resource
    private ManagedExecutorService executor;

    private ExternalTaskClient client;

    @PostConstruct
    public void start() {
        executor.submit(() -> {
            waitForCamunda();
            deployProcess();
            startExternalTaskClient();
        });
    }

    @PreDestroy
    public void stop() {
        if (client != null) {
            client.stop();
            logger.info("External Task Client stopped");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void waitForCamunda() {
        HttpClient http = HttpClient.newHttpClient();
        int maxAttempts = 30;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(CAMUNDA_BASE_URL + "/engine"))
                        .GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    logger.info("Camunda engine is ready after " + attempt + " attempt(s)");
                    return;
                }
            } catch (Exception e) {
                logger.info("Camunda not ready yet (attempt " + attempt + "/" + maxAttempts + ")");
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warning("Camunda engine did not become reachable – continuing anyway");
    }

    private void deployProcess() {
        try (InputStream stream = getClass().getResourceAsStream("/add-note-process.bpmn")) {
            if (stream == null) {
                logger.severe("BPMN resource /add-note-process.bpmn not found on classpath");
                return;
            }

            byte[] bpmnBytes = stream.readAllBytes();
            byte[] body      = buildMultipartBody(bpmnBytes);

            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CAMUNDA_BASE_URL + "/deployment/create"))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                logger.info("Process 'add-note-process' deployed successfully");
            } else {
                logger.warning("Deployment response " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            logger.severe("Could not deploy BPMN process: " + e.getMessage());
        }
    }

    /**
     * Builds a minimal multipart/form-data body for Camunda's /deployment/create endpoint.
     * Fields: deployment-name, deploy-changed-only, and the BPMN file itself.
     */
    private byte[] buildMultipartBody(byte[] bpmnBytes) {
        String CRLF = "\r\n";

        String header =
                "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"deployment-name\"" + CRLF + CRLF
                + "add-note-process" + CRLF

                + "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"deploy-changed-only\"" + CRLF + CRLF
                + "true" + CRLF

                + "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"add-note-process.bpmn\"; filename=\"add-note-process.bpmn\"" + CRLF
                + "Content-Type: application/xml" + CRLF + CRLF;

        String footer = CRLF + "--" + BOUNDARY + "--" + CRLF;

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + bpmnBytes.length + footerBytes.length];

        System.arraycopy(headerBytes, 0, body, 0,                         headerBytes.length);
        System.arraycopy(bpmnBytes,   0, body, headerBytes.length,        bpmnBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + bpmnBytes.length, footerBytes.length);

        return body;
    }

    private void startExternalTaskClient() {
        client = ExternalTaskClient.create()
                .baseUrl(CAMUNDA_BASE_URL)
                .asyncResponseTimeout(10_000)
                .lockDuration(10_000)
                .build();

        client.subscribe("add-note")
              .handler(contextService.createContextualProxy(addNoteHandler, ExternalTaskHandler.class))
              .open();

        logger.info("External Task Client started – subscribed to topic 'add-note'");
    }
}