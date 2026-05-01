package app.kumacheck.data.auth

/**
 * Background notification delivery strategy.
 *
 *  - [OFF] does nothing.
 *  - [INSTANT_NTFY] subscribes to an ntfy topic — Kuma pushes to ntfy, the
 *    app holds an SSE stream open in a `specialUse` FGS. Latency ≈ 1 sec,
 *    battery cost low (the SSE socket is silent until a message arrives).
 *  - [LIVE_MONITORING] holds the Kuma socket.io connection in a `specialUse`
 *    FGS. Same latency, higher battery cost (Kuma's socket is chatty).
 *
 * A previous "battery saver" mode was tried (WorkManager periodic poll) and
 * removed: JobScheduler defers periodic work indefinitely under Doze, so the
 * "every 15 min" promise turned into "whenever the OS feels like it." On a
 * sideloaded app the only honest answers to "wake me on outages" are the two
 * above. Old prefs that named the removed mode round-trip to OFF via
 * [fromString].
 */
enum class NotificationMode {
    OFF,
    INSTANT_NTFY,
    LIVE_MONITORING;

    companion object {
        fun fromString(v: String?): NotificationMode = when (v) {
            INSTANT_NTFY.name -> INSTANT_NTFY
            LIVE_MONITORING.name -> LIVE_MONITORING
            else -> OFF
        }
    }
}
