package ir.arman.api.dto;

/**
 * The inline response schema of GET /api/health (swagger.yaml, paths./api/health).
 * Field order matches the spec: status, timestamp, database.
 */
public record HealthResponse(String status, String timestamp, String database) {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    public static final String DB_CONNECTED = "connected";
    public static final String DB_DISCONNECTED = "disconnected";
}
