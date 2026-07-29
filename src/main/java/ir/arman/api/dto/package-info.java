/**
 * The request and response bodies of the spec, one record per schema.
 *
 * <h2>Why every type here carries {@code @RegisterForReflection}</h2>
 *
 * <p>Jackson serialises through reflection, and a native image only keeps the reflective
 * metadata the build could work out ahead of time. Quarkus works it out from the declared
 * return type of each resource method -- but almost every route in this application
 * returns {@code Uni<Response>}, because the same method answers with a DTO on success and
 * an {@link ir.arman.api.dto.ErrorResponse} on failure, and {@code Response} names neither.
 * The exception mappers have the same shape. So the build saw no types to register, and
 * the native image answered nearly every route with a 500:
 *
 * <pre>
 * No serializer found for class ir.arman.api.dto.HealthResponse and no properties
 * discovered to create BeanSerializer
 * </pre>
 *
 * <p>The JVM build never showed this -- reflection there needs no registration -- and the
 * integration tests that would have caught it are skipped by default. It was found by
 * running the packaged binary for task 8.5.
 *
 * <p>The annotation goes on every type in this package rather than only the ones observed
 * to fail, because which DTO a route happens to return is not something to keep track of
 * by hand. <strong>A new DTO added here needs the annotation too.</strong>
 *
 * <p>Entities are not annotated: the Hibernate extension registers them for reflection
 * itself, and nothing serialises one directly -- every response goes out as a record from
 * this package.
 */
package ir.arman.api.dto;
