package ir.arman.upload;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.file.FileSystem;
import ir.arman.api.dto.FileUploadResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Where uploaded files live (decision D4: the server's own disk).
 *
 * <p>Everything about the destination is configuration -- the directory, the cap, and the
 * two strings that make {@code cloudMetadata} look like a bucket report. That is the point
 * of putting it behind a bean: the day a real object store appears, this class is what
 * gets a second implementation, and neither the resource nor the response shape changes.
 *
 * <h2>Why copy and not move</h2>
 *
 * <p>Quarkus has already streamed the request body to a temporary file by the time a
 * {@link FileUpload} reaches us, so the obvious move is a rename. It is not used: the temp
 * directory and the upload directory are routinely on different filesystems -- in a
 * container they very often are -- and a rename across devices fails with {@code EXDEV}.
 * A copy always works, and Quarkus deletes the temporary file when the request ends.
 *
 * <h2>Names</h2>
 *
 * <p>{@code <epoch-seconds>_<original name>}, which is the shape the spec's example shows
 * ({@code 1722150000_report.pdf}). The original name is sanitised rather than trusted: it
 * arrives from the client, and {@code ../../etc/passwd} is a filename as far as multipart
 * is concerned. Unicode letters survive, so a Persian filename stays readable.
 *
 * <p>Two uploads of the same name in the same second would collide. The copy is what
 * detects it -- it refuses an existing target -- and the retry adds four random hex
 * characters. Nothing is ever silently overwritten.
 */
@ApplicationScoped
public class FileStore {

    /** The url prefix, and the object key prefix. Both are {@code uploads/} in the spec. */
    public static final String URL_PREFIX = "/uploads/";

    private static final int MAX_BASE_NAME = 100;
    private static final int MAX_EXTENSION = 16;
    private static final String FALLBACK_NAME = "file";

    /** Everything outside this becomes an underscore. {@code \p{L}} keeps Persian intact. */
    private static final String UNSAFE = "[^\\p{L}\\p{N}._-]";

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "app.upload.directory")
    String directory;

    @ConfigProperty(name = "app.upload.max-bytes")
    long maxBytes;

    @ConfigProperty(name = "app.upload.provider")
    String provider;

    @ConfigProperty(name = "app.upload.cdn-base-url")
    String cdnBaseUrl;

    private Path root;

    /**
     * The directory has to exist before the first upload, and creating it lazily would
     * mean every request paying for the check. Blocking at startup is not blocking a
     * request.
     */
    void createDirectory(@Observes StartupEvent event) {
        root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException(
                    "upload directory " + root + " could not be created", failure);
        }
    }

    /** The cap the resource enforces, in bytes. 50 MiB unless configured otherwise. */
    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Copies the upload into the store under a generated name and describes the result in
     * the shape the spec documents.
     */
    public Uni<FileUploadResponse> store(FileUpload upload) {
        String base = sanitise(upload.fileName());
        String source = upload.uploadedFile().toAbsolutePath().toString();

        return copyAs(source, Instant.now().getEpochSecond() + "_" + base)
                .onFailure().recoverWithUni(() ->
                        copyAs(source, Instant.now().getEpochSecond()
                                + "-" + randomSuffix() + "_" + base))
                .map(stored -> describe(stored, upload.size()));
    }

    /**
     * The absolute path a stored file sits at, or null if the name does not name one.
     *
     * <p>The name is re-sanitised and the result is checked to be a direct child of the
     * upload directory, so a traversal that survived the path template still cannot reach
     * outside. Returning null rather than throwing keeps "not there" and "not allowed"
     * indistinguishable to the caller, which is what the 404 on the serve route needs:
     * probing must not reveal what exists elsewhere on the disk.
     */
    public Path locate(String filename) {
        if (filename == null || filename.isBlank() || !filename.equals(sanitise(filename))) {
            return null;
        }

        Path candidate = root.resolve(filename).normalize();
        return candidate.getParent().equals(root) ? candidate : null;
    }

    /** Whether {@link #locate} points at a regular file that is actually there. */
    public Uni<Boolean> exists(Path file) {
        return fileSystem().lprops(file.toString())
                .map(props -> props.isRegularFile())
                .onFailure().recoverWithItem(false);
    }

    private Uni<String> copyAs(String source, String name) {
        return fileSystem().copy(source, root.resolve(name).toString()).replaceWith(name);
    }

    private FileUploadResponse describe(String name, long size) {
        String objectKey = URL_PREFIX.substring(1) + name;
        return new FileUploadResponse(
                true,
                URL_PREFIX + name,
                name,
                size,
                new FileUploadResponse.CloudMetadata(
                        cdnBaseUrl + URL_PREFIX + name, provider, objectKey));
    }

    /**
     * A client-supplied filename reduced to something safe to put on a disk and in a url.
     *
     * <p>Also the validator the serve route runs: a name is servable exactly when it comes
     * back from here unchanged, which is why this is a pure function of its input.
     */
    static String sanitise(String original) {
        if (original == null || original.isBlank()) {
            return FALLBACK_NAME;
        }

        // Strip any directory the client sent. Both separators, because the name may have
        // been produced on Windows and arrives as an opaque string either way.
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll(UNSAFE, "_");

        // A leading dot would make it a hidden file, and "." / ".." are not names at all.
        name = name.replaceFirst("^\\.+", "");
        if (name.isBlank()) {
            return FALLBACK_NAME;
        }

        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);

        if (base.length() > MAX_BASE_NAME) {
            base = base.substring(0, MAX_BASE_NAME);
        }
        if (extension.length() > MAX_EXTENSION) {
            extension = extension.substring(0, MAX_EXTENSION);
        }

        String trimmed = base + extension;
        return trimmed.isBlank() ? FALLBACK_NAME : trimmed;
    }

    /**
     * Four hex characters to break a name collision.
     *
     * <p>The {@link SecureRandom} is built here rather than held in a static field, and
     * that is not a style choice: a native image refuses to build with a Random in the
     * image heap, because an instance created during the build would ship with its seed
     * baked in and produce the same sequence in every container. This runs only when two
     * uploads of the same name land in the same second, so constructing one is free.
     */
    private static String randomSuffix() {
        byte[] bytes = new byte[2];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).toLowerCase(Locale.ROOT);
    }

    private FileSystem fileSystem() {
        return vertx.fileSystem();
    }
}
