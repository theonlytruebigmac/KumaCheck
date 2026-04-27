package app.kumacheck.notify.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pushes a [StatusSnapshot] into both widgets' Glance-backed preferences and
 * triggers a recompose. Safe to call from any context: it short-circuits if no
 * widgets of either type are placed.
 */
object SnapshotWriter {

    val KEY_TOTAL = intPreferencesKey("total")
    val KEY_UP = intPreferencesKey("up")
    val KEY_DOWN = intPreferencesKey("down")
    val KEY_MAINTENANCE = intPreferencesKey("maintenance")
    val KEY_PAUSED = intPreferencesKey("paused")
    val KEY_TIMESTAMP = longPreferencesKey("ts")
    /**
     * Rows encoded as: `namestatusping?\n...`. status is the
     * MonitorStatus.code int, ping is empty string if null. Picked over JSON
     * to keep the widget render path dependency-free.
     */
    val KEY_ROWS = stringPreferencesKey("rows")
    /** Sparkline pings as comma-separated ints, oldest → newest. */
    val KEY_SPARK = stringPreferencesKey("spark")

    /**
     * Serializes concurrent push() calls. Two pushes overlapping would otherwise
     * interleave the per-widget state writes — partial fields from one snapshot,
     * partial from another — and the recompose order between them is undefined.
     */
    private val pushMutex = Mutex()

    suspend fun push(context: Context, snapshot: StatusSnapshot) = withContext(Dispatchers.IO) {
        pushMutex.withLock {
            val mgr = GlanceAppWidgetManager(context)
            val tileIds = runCatching { mgr.getGlanceIds(StatusTileWidget::class.java) }
                .getOrDefault(emptyList())
            val listIds = runCatching { mgr.getGlanceIds(StatusListWidget::class.java) }
                .getOrDefault(emptyList())
            val microIds = runCatching { mgr.getGlanceIds(StatusMicroWidget::class.java) }
                .getOrDefault(emptyList())
            if (tileIds.isEmpty() && listIds.isEmpty() && microIds.isEmpty()) return@withLock

            val rowsBlob = encodeRows(snapshot.rows)
            val sparkBlob = snapshot.sparkPings.joinToString(",")
            val writeState: suspend (id: androidx.glance.GlanceId) -> Unit = { id ->
                updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { p ->
                    p.toMutablePreferences().apply {
                        this[KEY_TOTAL] = snapshot.total
                        this[KEY_UP] = snapshot.up
                        this[KEY_DOWN] = snapshot.down
                        this[KEY_MAINTENANCE] = snapshot.maintenance
                        this[KEY_PAUSED] = snapshot.paused
                        this[KEY_TIMESTAMP] = snapshot.timestampMs
                        this[KEY_ROWS] = rowsBlob
                        this[KEY_SPARK] = sparkBlob
                    }
                }
            }
            // Write all state first, then trigger recompose in a second pass —
            // a recompose mid-loop on one widget could otherwise read state for
            // a sibling widget that hasn't been updated yet on devices with
            // cross-widget shared state inspection.
            //
            // Each per-widget call is wrapped in runCatching so one widget's
            // DataStore-edit or recompose failure (e.g. a corrupt Glance
            // state file for a single instance) doesn't strand the rest of
            // the widgets at the previous snapshot.
            val tileWidget = StatusTileWidget()
            val listWidget = StatusListWidget()
            val microWidget = StatusMicroWidget()
            tileIds.forEach { id -> runCatching { writeState(id) } }
            listIds.forEach { id -> runCatching { writeState(id) } }
            microIds.forEach { id -> runCatching { writeState(id) } }
            tileIds.forEach { id -> runCatching { tileWidget.update(context, id) } }
            listIds.forEach { id -> runCatching { listWidget.update(context, id) } }
            microIds.forEach { id -> runCatching { microWidget.update(context, id) } }
        }
    }

    fun decodeSpark(blob: String?): List<Int> {
        if (blob.isNullOrBlank()) return emptyList()
        return blob.split(',').mapNotNull { it.toIntOrNull() }
    }

    fun encodeRows(rows: List<StatusSnapshot.Row>): String =
        rows.joinToString("\n") { r ->
            val ping = r.pingMs?.toString().orEmpty()
            // Strip every byte we use as a delimiter (SOH field separator,
            // LF row separator, CR which would also break the row split on
            // some platforms) so a monitor name with newlines or control
            // chars can't corrupt the encoded blob.
            val safeName = r.name
                .replace('', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                // Cap so a multi-KB monitor name can't bloat the per-widget
                // DataStore blob and force the widget recompose to handle huge
                // strings on every snapshot push.
                .take(64)
            "$safeName${r.status.code}$ping"
        }

    fun decodeRows(blob: String?): List<StatusSnapshot.Row> {
        if (blob.isNullOrEmpty()) return emptyList()
        return blob.split('\n').mapNotNull { line ->
            val parts = line.split('')
            if (parts.size < 3) return@mapNotNull null
            val name = parts[0]
            val status = parts[1].toIntOrNull()
                ?.let { code -> app.kumacheck.data.model.MonitorStatus.from(code) }
                ?: app.kumacheck.data.model.MonitorStatus.UNKNOWN
            val ping = parts[2].toIntOrNull()
            StatusSnapshot.Row(name, status, ping)
        }
    }
}
