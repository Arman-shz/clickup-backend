package ir.arman.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyMaterial} without CDI: the observer method is called directly, which is all
 * the class does anyway.
 *
 * <p>These are the tests that matter for a decision the deployment cannot see -- whether
 * a container that was given no key material generates a usable one, and whether one that
 * <em>was</em> given key material leaves it alone. Getting the second wrong would mean a
 * restart silently replacing the signing key and invalidating every token in flight, and
 * nothing about that failure points at this class.
 */
class KeyMaterialTest {

    private static KeyMaterial pointedAt(Path privateKey, Path publicKey) {
        KeyMaterial keys = new KeyMaterial();
        keys.signLocation = privateKey.toString();
        keys.verifyLocation = publicKey.toString();
        return keys;
    }

    @Test
    void generatesAUsableRsaPairWhenNeitherFileExists(@TempDir Path directory) throws Exception {
        Path privateKey = directory.resolve("keys/jwt-private.pem");
        Path publicKey = directory.resolve("keys/jwt-public.pem");

        pointedAt(privateKey, publicKey).generateIfAbsent(null);

        // Parsing them back is the only assertion that means anything: PEM that looks
        // right and does not decode would fail at the first login, not here.
        RSAPrivateKey parsedPrivate = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der(privateKey, "PRIVATE KEY")));
        RSAPublicKey parsedPublic = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der(publicKey, "PUBLIC KEY")));

        assertEquals(2048, parsedPrivate.getModulus().bitLength());
        // The two halves have to be halves of the same key, which is exactly what a
        // matching modulus says.
        assertEquals(parsedPrivate.getModulus(), parsedPublic.getModulus());
    }

    @Test
    void createsTheDirectoryItWasPointedAt(@TempDir Path directory) {
        Path privateKey = directory.resolve("not/there/yet/jwt-private.pem");

        pointedAt(privateKey, directory.resolve("not/there/yet/jwt-public.pem"))
                .generateIfAbsent(null);

        assertTrue(Files.isRegularFile(privateKey),
                "an empty volume has no directories in it either");
    }

    @Test
    void keepsThePrivateKeyToItsOwner(@TempDir Path directory) throws IOException {
        Path privateKey = directory.resolve("jwt-private.pem");

        pointedAt(privateKey, directory.resolve("jwt-public.pem")).generateIfAbsent(null);

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(privateKey));
    }

    @Test
    void leavesAnExistingPairCompletelyAlone(@TempDir Path directory) throws IOException {
        Path privateKey = directory.resolve("jwt-private.pem");
        Path publicKey = directory.resolve("jwt-public.pem");
        pointedAt(privateKey, publicKey).generateIfAbsent(null);

        String wasPrivate = Files.readString(privateKey);
        String wasPublic = Files.readString(publicKey);

        // The restart. This is the one that would be catastrophic and invisible: a second
        // start that regenerates means every access token issued before it stops
        // verifying, and the only symptom is users being logged out for no reason.
        pointedAt(privateKey, publicKey).generateIfAbsent(null);

        assertEquals(wasPrivate, Files.readString(privateKey));
        assertEquals(wasPublic, Files.readString(publicKey));
    }

    @Test
    void refusesToStartOnHalfAPair(@TempDir Path directory) throws IOException {
        Path privateKey = directory.resolve("jwt-private.pem");
        Path publicKey = directory.resolve("jwt-public.pem");
        pointedAt(privateKey, publicKey).generateIfAbsent(null);
        Files.delete(publicKey);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> pointedAt(privateKey, publicKey).generateIfAbsent(null));

        // The message has to name the missing file. An operator reading a container log
        // has nothing else to go on.
        assertTrue(refused.getMessage().contains(publicKey.toString()), refused.getMessage());
    }

    @Test
    void twoDeploymentsDoNotShareAKey(@TempDir Path first, @TempDir Path second) throws IOException {
        pointedAt(first.resolve("jwt-private.pem"), first.resolve("jwt-public.pem"))
                .generateIfAbsent(null);
        pointedAt(second.resolve("jwt-private.pem"), second.resolve("jwt-public.pem"))
                .generateIfAbsent(null);

        // The reason the key is generated at runtime rather than baked into the image.
        assertNotEquals(
                Files.readString(first.resolve("jwt-private.pem")),
                Files.readString(second.resolve("jwt-private.pem")));
    }

    @Test
    void writesNothingWhenTheKeysAreOnTheClasspath(@TempDir Path directory) {
        // Development and test: the locations name packaged resources, and treating them
        // as missing files would write a jwt/ directory into the project root and then
        // sign with a key that is not the committed one.
        KeyMaterial keys = new KeyMaterial();
        keys.signLocation = "jwt/dev-privateKey.pem";
        keys.verifyLocation = "jwt/dev-publicKey.pem";

        keys.generateIfAbsent(null);

        assertTrue(Files.notExists(Path.of("jwt/dev-privateKey.pem")),
                "the development key location must not be created on disk");
        assertTrue(isEmpty(directory), "nothing at all should have been written");
    }

    @Test
    void recognisesTheTwoPrefixesSmallRyeAccepts() {
        assertEquals(null, KeyMaterial.filesystemPath("classpath:jwt/dev-privateKey.pem"),
                "an explicit classpath: location is never a file");
        assertEquals(null, KeyMaterial.filesystemPath("jwt/dev-privateKey.pem"),
                "a bare location that resolves on the classpath is not a file either");
        assertEquals(Path.of("jwt/dev-privateKey.pem"),
                KeyMaterial.filesystemPath("file:jwt/dev-privateKey.pem"),
                "file: means the filesystem even when the same name exists on the classpath");
        assertEquals(Path.of("/data/keys/jwt-private.pem"),
                KeyMaterial.filesystemPath("/data/keys/jwt-private.pem"));
        assertEquals(null, KeyMaterial.filesystemPath(""));
        assertEquals(null, KeyMaterial.filesystemPath(null));
    }

    private static boolean isEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    /** The bytes inside a PEM envelope, and a check that the envelope is the right one. */
    private static byte[] der(Path pem, String label) throws IOException {
        String text = Files.readString(pem, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";

        assertTrue(text.startsWith(begin + "\n"), pem + " does not open with " + begin);
        assertTrue(text.endsWith(end + "\n"), pem + " does not close with " + end);

        String body = text.substring(begin.length(), text.length() - end.length() - 1);
        return Base64.getMimeDecoder().decode(body);
    }
}
