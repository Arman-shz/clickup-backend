package ir.arman.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import ir.arman.api.dto.ClientLogRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes client log entries to {@code app.log} and {@code error.log} (decision D7).
 *
 * <h2>One entry is one line of JSON</h2>
 *
 * <p>JSON Lines, not free text, and Jackson does the rendering rather than string
 * concatenation. That is not about tidiness: a {@code message} containing a newline would
 * otherwise become two lines, the second of which anything reading the file would take
 * for a separate entry that nobody wrote. Escaping is what prevents a caller forging log
 * entries, so the line is always built by the serialiser.
 *
 * <h2>Why a lock</h2>
 *
 * <p>Appends are serialised through a {@link ReentrantLock} and performed on a worker
 * thread, never on the event loop. A lock across the whole write is what makes rotation
 * safe -- checking the size and then renaming is two operations, and without the lock a
 * concurrent append could land in the file between them and be lost. On an admin-only
 * diagnostic route the throughput this costs is not worth a cleverer scheme.
 *
 * <h2>Rotation</h2>
 *
 * <p>Size-based, one generation kept: at the cap the file becomes {@code app.log.1},
 * replacing whatever was there, and a fresh one starts. So the directory holds at most
 * twice {@code app.logs.max-bytes} per file and cannot grow without bound -- the missing
 * half of "write it to a file" that nothing else in this project supplies, since there is
 * no logging stack in compose to collect or trim anything.
 */
@ApplicationScoped
public class ClientLogWriter {

    private static final Logger LOG = Logger.getLogger(ClientLogWriter.class);

    /** Past this many bytes of rendered JSON, a context is replaced by a marker. */
    private static final int MAX_CONTEXT_BYTES = 8192;

    static final String APP_LOG = "app.log";
    static final String ERROR_LOG = "error.log";
    static final String ROTATED_SUFFIX = ".1";

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.logs.directory")
    String directory;

    @ConfigProperty(name = "app.logs.max-bytes")
    long maxBytes;

    private final ReentrantLock lock = new ReentrantLock();

    private Path appLog;
    private Path errorLog;

    void createDirectory(@Observes StartupEvent event) {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "log directory " + root + " could not be created", failure);
        }
        appLog = root.resolve(APP_LOG);
        errorLog = root.resolve(ERROR_LOG);
    }

    /**
     * Appends the entry to {@code app.log}, and to {@code error.log} as well when the
     * level is {@code error}.
     *
     * <p>Runs on a worker thread: writing to a file is blocking however it is dressed up,
     * and the event loop is not the place for it.
     */
    public Uni<Void> write(ClientLogRequest request, String userId) {
        String line = render(request, userId);
        boolean isError = request.level() == LogLevel.ERROR;

        return Uni.createFrom().<Void>item(() -> {
                    append(line, isError);
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /** Where the entries go, for anything that needs to read them back. */
    public Path appLog() {
        return appLog;
    }

    public Path errorLog() {
        return errorLog;
    }

    private void append(String line, boolean isError) {
        lock.lock();
        try {
            appendTo(appLog, line);
            if (isError) {
                appendTo(errorLog, line);
            }
        } catch (IOException failure) {
            // The client is told the entry was accepted either way. A frontend cannot do
            // anything useful with "the log file is full", and turning a disk problem into
            // a failed request would make every client error report a second error report.
            LOG.errorf(failure, "client log entry could not be written to %s", directory);
        } finally {
            lock.unlock();
        }
    }

    private void appendTo(Path file, String line) throws IOException {
        rotateIfFull(file);
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void rotateIfFull(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) < maxBytes) {
            return;
        }
        Path rotated = file.resolveSibling(file.getFileName() + ROTATED_SUFFIX);
        Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The entry as one line of JSON, newline included.
     *
     * <p>{@code userId} is the account whose token carried the request. It is recorded
     * because the route is admin-only (D9): every entry has exactly one person who could
     * have sent it, and a log that does not say who is worth less than one that does.
     */
    private String render(ClientLogRequest request, String userId) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        entry.put("level", request.level().value());
        entry.put("userId", userId);
        entry.put("message", request.message());

        if (request.context() != null && !request.context().isEmpty()) {
            entry.set("context", context(request.context()));
        }

        try {
            return mapper.writeValueAsString(entry) + "\n";
        } catch (JsonProcessingException impossible) {
            // Every node in the tree came from Jackson's own factory.
            throw new IllegalStateException("log entry could not be rendered", impossible);
        }
    }

    /**
     * The context, or a marker in its place if it is too big to belong on one line.
     *
     * <p>Dropping the detail rather than refusing the entry is deliberate: the message is
     * the part worth keeping, and an error report lost because its context was verbose
     * would be the worst possible failure for this route.
     */
    private com.fasterxml.jackson.databind.JsonNode context(java.util.Map<String, Object> raw) {
        try {
            String rendered = mapper.writeValueAsString(raw);
            if (rendered.getBytes(StandardCharsets.UTF_8).length <= MAX_CONTEXT_BYTES) {
                return mapper.valueToTree(raw);
            }
            ObjectNode truncated = mapper.createObjectNode();
            truncated.put("_truncated", true);
            truncated.put("_bytes", rendered.getBytes(StandardCharsets.UTF_8).length);
            return truncated;
        } catch (JsonProcessingException unserialisable) {
            ObjectNode failed = mapper.createObjectNode();
            failed.put("_unserialisable", true);
            return failed;
        }
    }
}
