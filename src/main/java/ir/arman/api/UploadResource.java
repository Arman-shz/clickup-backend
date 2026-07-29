package ir.arman.api;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.upload.FileStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * POST /api/upload -- one multipart part named {@code file}, up to 50 MiB.
 *
 * <p>The response is the spec's FileUploadResponse, whose {@code url} is served back by
 * {@link UploadedFileResource}. Where the bytes actually go, and why {@code cloudMetadata}
 * is a fiction, is decision D4 -- see {@link FileStore}.
 *
 * <h2>Two limits, and why there are two</h2>
 *
 * <p>The documented one is 50 MiB and it is enforced here, in Java, so going over it
 * produces the spec's error body with a 413. Behind it sits
 * {@code quarkus.http.limits.max-body-size}, set a little higher: the multipart envelope
 * adds boundaries and headers to the request, so a request carrying a legal 50 MiB file is
 * itself slightly larger than 50 MiB, and a container limit set exactly at the cap would
 * reject files the spec allows.
 *
 * <p>The backstop answers a bare 413 with no body, because Vert.x aborts the request
 * before any JAX-RS code runs and no exception mapper can reach it. That is deliberate
 * rather than overlooked: shaping that response would mean buffering a body we have
 * already decided is too big to buffer.
 */
@Path("/api/upload")
@Authenticated
public class UploadResource {

    @Inject
    FileStore store;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> upload(@RestForm("file") FileUpload file) {
        if (file == null) {
            return Uni.createFrom().item(badRequest(ApiMessages.FILE_REQUIRED));
        }

        // A zero-byte part is a mistake at the other end -- a cancelled picker, a file
        // that was moved. Storing it would hand back a url to nothing, and the client
        // would have no way to tell that from a successful upload.
        if (file.size() == 0) {
            return Uni.createFrom().item(badRequest(ApiMessages.FILE_EMPTY));
        }

        if (file.size() > store.maxBytes()) {
            return Uni.createFrom().item(tooLarge());
        }

        return store.store(file).map(stored -> Response.ok(stored).build());
    }

    /** components/responses/BadRequest. */
    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(message))
                .build();
    }

    /** paths./api/upload.post.responses.413. */
    private static Response tooLarge() {
        return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .entity(ErrorResponse.of(ApiMessages.FILE_TOO_LARGE))
                .build();
    }
}
