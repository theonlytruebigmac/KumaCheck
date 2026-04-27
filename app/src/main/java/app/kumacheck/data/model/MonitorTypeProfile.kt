package app.kumacheck.data.model

/**
 * Per-Kuma-monitor-type rendering profile. Single source of truth for how each
 * monitor type should be presented in the UI.
 *
 * The hero card on Monitor Detail and the trailing slot on the Overview rows
 * branch on [DisplayMode] to pick a layout (response-time chart vs status
 * strip), and use the type-specific healthy/unhealthy verbiage so a Docker
 * container reads "Healthy / Unhealthy" rather than the generic "Up / Down".
 *
 * Extending this for a new Kuma type means adding one entry to [REGISTRY] —
 * everything downstream picks it up automatically. Types we don't recognise
 * fall back to a safe default ([UNKNOWN_PROFILE]) so the UI never crashes on
 * a new server-side type before the app is updated.
 */
data class MonitorTypeProfile(
    val displayName: String,
    val mode: DisplayMode,
    val healthyVerb: String,
    val unhealthyVerb: String,
)

enum class DisplayMode {
    /** Response-time monitors: ping/http/dns/port/db/etc. — show ms + chart. */
    LATENCY,
    /** State-only monitors: docker/push/manual — show status word + recent strip. */
    STATE,
    /** Aggregate of children (group). Today renders like STATE; future per-child grid. */
    AGGREGATE,
}

object MonitorTypes {
    /**
     * Known Kuma monitor types as of v1.23.x. Keys are the lowercase server-side
     * type strings; lookup is case-insensitive via [forType].
     */
    private val REGISTRY: Map<String, MonitorTypeProfile> = mapOf(
        // --- Latency-producing types ---
        "ping" to MonitorTypeProfile("Ping", DisplayMode.LATENCY, "Reachable", "Unreachable"),
        "tailscale-ping" to MonitorTypeProfile("Tailscale Ping", DisplayMode.LATENCY, "Reachable", "Unreachable"),
        "http" to MonitorTypeProfile("HTTP(s)", DisplayMode.LATENCY, "OK", "Failing"),
        "keyword" to MonitorTypeProfile("HTTP Keyword", DisplayMode.LATENCY, "Found", "Missing"),
        "json-query" to MonitorTypeProfile("JSON Query", DisplayMode.LATENCY, "Match", "No Match"),
        "port" to MonitorTypeProfile("TCP Port", DisplayMode.LATENCY, "Open", "Closed"),
        "dns" to MonitorTypeProfile("DNS", DisplayMode.LATENCY, "Resolved", "Failing"),
        "mqtt" to MonitorTypeProfile("MQTT", DisplayMode.LATENCY, "Connected", "Disconnected"),
        "grpc-keyword" to MonitorTypeProfile("gRPC", DisplayMode.LATENCY, "OK", "Failing"),
        "kafka-producer" to MonitorTypeProfile("Kafka", DisplayMode.LATENCY, "OK", "Failing"),
        "steam" to MonitorTypeProfile("Steam Server", DisplayMode.LATENCY, "Online", "Offline"),
        "gamedig" to MonitorTypeProfile("Game Server", DisplayMode.LATENCY, "Online", "Offline"),
        "real-browser" to MonitorTypeProfile("Browser", DisplayMode.LATENCY, "OK", "Failing"),
        "mongodb" to MonitorTypeProfile("MongoDB", DisplayMode.LATENCY, "Connected", "Disconnected"),
        "mysql" to MonitorTypeProfile("MySQL", DisplayMode.LATENCY, "Connected", "Disconnected"),
        "postgres" to MonitorTypeProfile("Postgres", DisplayMode.LATENCY, "Connected", "Disconnected"),
        "redis" to MonitorTypeProfile("Redis", DisplayMode.LATENCY, "Connected", "Disconnected"),
        "sqlserver" to MonitorTypeProfile("SQL Server", DisplayMode.LATENCY, "Connected", "Disconnected"),
        "radius" to MonitorTypeProfile("RADIUS", DisplayMode.LATENCY, "Authenticated", "Failing"),
        "snmp" to MonitorTypeProfile("SNMP", DisplayMode.LATENCY, "OK", "Failing"),
        "smtp" to MonitorTypeProfile("SMTP", DisplayMode.LATENCY, "OK", "Failing"),

        // --- State-only types ---
        "docker" to MonitorTypeProfile("Docker", DisplayMode.STATE, "Healthy", "Unhealthy"),
        "push" to MonitorTypeProfile("Push", DisplayMode.STATE, "Receiving", "Silent"),
        "manual" to MonitorTypeProfile("Manual", DisplayMode.STATE, "Marked Up", "Marked Down"),

        // --- Aggregate ---
        "group" to MonitorTypeProfile("Group", DisplayMode.AGGREGATE, "All Up", "Has Down"),
    )

    private val UNKNOWN_PROFILE = MonitorTypeProfile(
        displayName = "Monitor",
        mode = DisplayMode.STATE,
        healthyVerb = "Up",
        unhealthyVerb = "Down",
    )

    fun forType(type: String?): MonitorTypeProfile =
        type?.lowercase()?.let { REGISTRY[it] } ?: UNKNOWN_PROFILE

    /** True when a profile is known — caller can use raw type uppercase otherwise. */
    fun isKnown(type: String?): Boolean =
        type?.lowercase()?.let { REGISTRY.containsKey(it) } == true
}
