package ir.arman.api.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The spec's FileUploadResponse schema (swagger.yaml, components/schemas/FileUploadResponse).
 *
 * <p>{@code url} is server-relative and resolves against this API:
 * {@code GET /uploads/{filename}} serves the bytes back. That route is not in the original
 * spec -- it was added, because without it every url this response hands out is a 404.
 *
 * <p><strong>{@code cloudMetadata} is synthesised, not reported.</strong> Nothing here
 * talks to an object store; the file is on the server's own disk. The block is filled from
 * configuration so the response keeps the shape the spec documents and a client written
 * against it does not break the day a real bucket appears -- but {@code cdnUrl} currently
 * names a host that serves nothing, and {@code provider} is a string in a properties file.
 * That is decision D4, and it is written down in swagger.yaml as well so nobody reads this
 * block as evidence the file is replicated somewhere.
 *
 * <p>{@code size} is the byte count of what was actually stored, taken from the upload
 * rather than from anything the client said about it.
 */
@RegisterForReflection
public record FileUploadResponse(
        boolean success,
        String url,
        String filename,
        long size,
        CloudMetadata cloudMetadata) {

    /**
     * The nested object of the same schema. A record of its own rather than a Map so the
     * three property names are in the type system, where a typo is a compile error.
     */
    @RegisterForReflection
    public record CloudMetadata(String cdnUrl, String provider, String objectKey) {
    }
}
