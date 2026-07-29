package ir.arman.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

/**
 * The RSA keypair that signs and verifies access tokens, created on first start when the
 * deployment has not supplied one (task 11.4).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Production reads its keypair from {@code JWT_PRIVATE_KEY_LOCATION} and
 * {@code JWT_PUBLIC_KEY_LOCATION}, defaulting to two files in the mounted keys volume.
 * Before this class those two variables were mandatory and had no defaults, so the
 * container could not start at all until somebody produced a keypair by hand. Generating
 * one at build time was never an option -- it would be baked into the image, identical in
 * every deployment, and readable by anyone who can pull the image.
 *
 * <p>So the pair is made here, at runtime, into a volume: unique to the deployment,
 * absent from the image, and stable across restarts, which is what keeps already-issued
 * tokens verifiable when the container is replaced.
 *
 * <h2>The rule, exactly</h2>
 *
 * <p>A pair is generated when the configured location is <em>not</em> a classpath resource
 * and <em>neither</em> file exists yet. The classpath test is what keeps development and
 * test alone: there the locations name {@code jwt/dev-privateKey.pem} and its public half,
 * which are packaged resources, so this class finds them and does nothing.
 *
 * <p>If exactly one of the two exists, startup fails naming the file. That state is only
 * reachable by a crash between the two writes below, or by an operator supplying half a
 * pair, and both deserve a person rather than a guess: deleting the survivor would destroy
 * a key this class may not have created, and regenerating over it would invalidate every
 * token already issued.
 *
 * <h2>What this costs</h2>
 *
 * <p>Said plainly, because the convenience hides it: a deployment that never sets those
 * variables runs forever on a key nobody chose, nobody wrote down, and nobody rotates. The
 * key is exactly as safe as the volume it sits in, and anyone who can read that volume can
 * mint an access token for any account. That is the price of `docker compose up -d`
 * working with no setup at all, and it is the right trade for a deployment nobody has
 * configured -- but it is not a substitute for supplying a managed key.
 */
@ApplicationScoped
public class KeyMaterial {

    private static final Logger LOG = Logger.getLogger(KeyMaterial.class);

    /** RSA-2048, which is what {@code smallrye.jwt} signs RS256 with by default. */
    private static final int KEY_SIZE = 2048;

    private static final String PRIVATE_LABEL = "PRIVATE KEY";
    private static final String PUBLIC_LABEL = "PUBLIC KEY";

    /** SmallRye accepts either prefix on a key location; both have to be understood here. */
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";

    @ConfigProperty(name = "smallrye.jwt.sign.key.location")
    String signLocation;

    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    String verifyLocation;

    /**
     * Runs before anything can sign or verify a token, which is any time after startup:
     * nothing in the application touches a key while it is still starting.
     *
     * <p>The priority puts it ahead of {@link AdminBootstrap}, which is the one startup
     * task that could plausibly want a working keypair.
     */
    void generateIfAbsent(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent event) {
        Path privateKey = filesystemPath(signLocation);
        Path publicKey = filesystemPath(verifyLocation);

        if (privateKey == null || publicKey == null) {
            LOG.debugf("JWT keys come from the classpath (%s), nothing to generate", signLocation);
            return;
        }

        boolean hasPrivate = Files.isReadable(privateKey);
        boolean hasPublic = Files.isReadable(publicKey);

        if (hasPrivate && hasPublic) {
            LOG.infof("Signing tokens with the existing keypair at %s", privateKey);
            return;
        }
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException(
                    "half a JWT keypair: " + (hasPrivate ? publicKey : privateKey)
                            + " is missing while " + (hasPrivate ? privateKey : publicKey)
                            + " is present. Supply the missing half, or delete both and let"
                            + " the application generate a new pair -- which invalidates"
                            + " every access token already issued.");
        }

        generate(privateKey, publicKey);
    }

    /**
     * Writes a fresh pair, each file built beside its destination and then moved into
     * place, so a reader never sees a half-written key.
     *
     * <p>The public half is moved first. The two moves are not one atomic act, and a crash
     * between them leaves the state the observer above refuses to start from -- landing on
     * the public key first means the leftover is the harmless one.
     */
    private void generate(Path privateKey, Path publicKey) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();

            Files.createDirectories(privateKey.toAbsolutePath().getParent());
            Files.createDirectories(publicKey.toAbsolutePath().getParent());

            install(publicKey, PUBLIC_LABEL, pair.getPublic().getEncoded(), false);
            install(privateKey, PRIVATE_LABEL, pair.getPrivate().getEncoded(), true);

            LOG.warnf("No JWT keypair was configured, so one was generated at %s."
                    + " It is unique to this deployment and lives only in that directory:"
                    + " lose it and every issued token stops verifying; leak it and anyone"
                    + " can forge one. Set JWT_PRIVATE_KEY_LOCATION and"
                    + " JWT_PUBLIC_KEY_LOCATION to use a key you manage.", privateKey);
        } catch (NoSuchAlgorithmException noRsa) {
            // Only reachable if the runtime ships without RSA -- which for a native image
            // would mean the security services were left out of the build.
            throw new IllegalStateException("RSA key generation is unavailable", noRsa);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "JWT keypair could not be written to " + privateKey.getParent(), failure);
        }
    }

    private static void install(Path destination, String label, byte[] der, boolean ownerOnly)
            throws IOException {

        Path staged = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(staged, pem(label, der), StandardCharsets.US_ASCII);
        if (ownerOnly) {
            restrictToOwner(staged);
        }

        try {
            Files.move(staged, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            // Some volume drivers do not offer it. A non-atomic move is still better than
            // writing the key in place, which would expose a truncated file to a reader.
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** PKCS#8 for the private half, X.509 SubjectPublicKeyInfo for the public one. */
    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    /**
     * 0600. Best effort: a volume on a filesystem without POSIX permissions simply does
     * not get them, and refusing to start over that would be worse than the exposure.
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException noPosix) {
            LOG.warnf("Could not restrict %s to its owner: %s", file, noPosix.getMessage());
        }
    }

    /**
     * The file this location names, or null when it names a classpath resource instead.
     *
     * <p>An explicit {@code classpath:} prefix settles it; otherwise a bare location is
     * resolved against the classpath first, which is how {@code jwt/dev-privateKey.pem}
     * is recognised as the packaged development key rather than as a file to create.
     */
    static Path filesystemPath(String location) {
        if (location == null || location.isBlank() || location.startsWith(CLASSPATH_PREFIX)) {
            return null;
        }

        String bare = location.startsWith(FILE_PREFIX)
                ? location.substring(FILE_PREFIX.length())
                : location;

        if (!location.startsWith(FILE_PREFIX) && onClasspath(bare)) {
            return null;
        }
        return Path.of(bare);
    }

    private static boolean onClasspath(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = KeyMaterial.class.getClassLoader();
        }
        return loader.getResource(resource) != null;
    }
}
