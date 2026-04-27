package app.kumacheck.notify.widget

import app.kumacheck.data.model.MonitorStatus

/**
 * Snapshot pushed to home-screen widgets. Built by [SnapshotWriter] and read
 * back by the Glance widgets via their preferences-backed state.
 *
 * Kept deliberately small — widget state is shared across the AppWidgetService
 * boundary, so anything that lands here must round-trip through string-keyed
 * preferences cleanly.
 */
data class StatusSnapshot(
    val total: Int,
    val up: Int,
    val down: Int,
    val maintenance: Int,
    val paused: Int,
    val timestampMs: Long,
    val rows: List<Row>,
    /**
     * Cross-monitor average ping per recent-beat index (oldest → newest, max 20).
     * Used by widget sparklines. Empty when no beats are buffered.
     */
    val sparkPings: List<Int> = emptyList(),
) {
    data class Row(val name: String, val status: MonitorStatus, val pingMs: Int?)
}
