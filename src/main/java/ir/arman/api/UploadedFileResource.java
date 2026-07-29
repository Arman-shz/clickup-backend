package ir.arman.api;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import ir.arman.api.dto.ErrorResponse;
import ir.arman.api.error.ApiMessages;
import ir.arman.upload.FileStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GET /uploads/{filename} -- serves back what POST /api/upload stored.
 *
 * <p><strong>This route is not in the original spec.</strong> It was added under decision
 * D4, because the spec has uploads return a {@code url} and then declares nothing that
 * answers at it: without this, every successful upload hands the client a 404.
 *
 * <p>It is authenticated, like every other route outside /api/auth and /api/health. The
 * cost lands on the frontend and is the same one the chat stream already carries: a
 * browser will not put an Authorization header on {@code <img src>}, so a client showing
 * an uploaded image has to fetch it and hand the tag a blob url. The alternative was
 * leaving every file anyone uploads readable by anyone who learns the name, and the names
 * are a timestamp plus the original filename -- not a secret.
 *
 * <h2>Content types are narrowed on the way out</h2>
 *
 * <p>These are files the users chose, served from the API's own origin. Serving them back
 * under whatever type their extension suggests would mean an uploaded {@code .html} --
 * or a {@code .svg}, which carries script just as well -- executing on this origin with
 * whatever the browser has for it. So only image types known to be inert render inline.
 * Everything else, including PDF, goes out as {@code application/octet-stream} with
 * {@code Content-Disposition: attachment}, and every response carries {@code nosniff} so
 * the browser does not go looking for a better idea.
 */
@Path("/uploads")
@Authenticated
public class UploadedFileResource {

    /** Image types with no scripting surface. Notably absent: image/svg+xml. */
    private static final Map<String, String> INLINE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "avif", "image/avif",
            "bmp", "image/bmp");

    private static final Set<String> INLINE_EXTENSIONS = INLINE_TYPES.keySet();

    @Inject
    FileStore store;

    @GET
    @Path("/{filename}")
    public Uni<Response> download(@PathParam("filename") String filename) {
        java.nio.file.Path file = store.locate(filename);
        if (file == null) {
            return Uni.createFrom().item(notFound());
        }

        return store.exists(file).map(there -> there ? serve(file, filename) : notFound());
    }

    private static Response serve(java.nio.file.Path file, String filename) {
        String extension = extensionOf(filename);
        boolean inline = INLINE_EXTENSIONS.contains(extension);

        return Response.ok(file.toFile())
                .type(inline ? INLINE_TYPES.get(extension) : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition",
                        (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                                + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * components/responses/NotFound. The same answer for a name that is not there and a
     * name that was never allowed -- see {@link FileStore#locate}.
     */
    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ErrorResponse.of(ApiMessages.NOT_FOUND))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
