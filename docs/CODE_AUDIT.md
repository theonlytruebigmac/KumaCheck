# KumaCheck code audit

Living document — findings from a multi-pass review of the codebase. Items
are tagged by severity and verified against the source. False positives that
came up during review are listed at the bottom so they're not re-discovered
in a future pass.

Severity legend:

- **Critical** — security or data-loss; fix before next release.
- **High** — functional bug, race, or hardening gap that users will hit.
- **Medium** — quality / maintenance debt; do soon.
- **Low** — polish, nice-to-have, future-proofing.

Status legend: `open` (not started), `wip`, `done`.

---

## Pass 1 — Cross-cutting review

### Security

#### S1. Plaintext token fallback when AndroidKeyStore is unavailable — High — done

[`KumaPrefs.kt:778`](../app/src/main/java/app/kumacheck/data/auth/KumaPrefs.kt#L778)

```kotlin
return crypto.encrypt(plain) ?: plain
```

If AndroidKeyStore is transiently unavailable (custom ROM, device policy,
hardware fault), the JWT is silently persisted to DataStore in plaintext.
The `tokensStoredPlaintext` Flow surfaces the state via a banner, but the
token is already on disk readable to root / backup-extractor by the time the
user sees it.

**Fix:** don't persist on encryption failure — keep the token in-memory only
(or fail the login with a "secure storage unavailable" error) and let the
user retry once the keystore is reachable.

#### S2. Cleartext traffic permitted globally — Medium — done

[`network_security_config.xml:14`](../app/src/main/res/xml/network_security_config.xml#L14)

The current comment correctly notes that Android `<domain>` matchers can't
do CIDR. But the global permit means a phishing redirect to
`http://attacker.com:3001` would silently work.

**Fix:** enumerate private ranges in a `<domain-config>`
(`127.0.0.1`, `10.0.0.0/8`, `192.168.0.0/16`, `172.16.0.0/12`, `localhost`,
`*.lan`, `*.local`) and leave `<base-config>` HTTPS-only. Add a persistent
in-app banner whenever the active server is `http://` and the host is *not*
private. The login warning fires once; the banner should fire every session.

#### S3. `info` event JSON logged unredacted — Low — done

[`KumaSocket.kt:257`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L257)

`Log.i(TAG, "info: $o")` dumps the raw `JSONObject`. A forked / malicious
server could stuff anything into the payload and it would land in logcat.
On Android 12+ logcat is restricted, but the logging principle still stands.

**Fix:** log the parsed `ServerInfo` (version / dbType / timezone) only.

---

### Bugs

#### B1. Mute toggle is a read-modify-write race — High — done

[`MonitorDetailViewModel.kt:130-136`](../app/src/main/java/app/kumacheck/ui/detail/MonitorDetailViewModel.kt#L130-L136)

```kotlin
val current = prefs.mutedMonitorIds.first().toMutableSet()
if (muted) current.add(monitorId) else current.remove(monitorId)
prefs.setMutedMonitorIds(current)
```

Two rapid toggles (or a notification handler racing a UI tap) can drop a
mutation. Same pattern likely exists for `pinnedMonitorIds`.

**Fix:** do the mutation atomically inside `dataStore.edit { … }`. Sweep
KumaPrefs for any other read-then-write Set/Map operations.

#### B2. `parseServerDate` `!!` inside `runCatching` — Low — done

[`MaintenanceEditViewModel.kt:283`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditViewModel.kt#L283)

`runCatching { return fmt.parse(s)!!.time }` — functional, but the `!!` is
dishonest. A real null is swallowed identically to a parse exception.

**Fix:** `fmt.parse(s)?.time?.let { return it }` outside `runCatching`, and
keep `runCatching` only around the parse call so locale errors propagate
cleanly to the next pattern.

#### B3. AutoReauth gap window swallows rapid failures — Low — done (documented)

[`AutoReauth.kt:50-85`](../app/src/main/java/app/kumacheck/AutoReauth.kt#L50-L85)

`AUTO_REAUTH_FAILURE_MIN_GAP_MS` (5 s) means a server flapping every 2 s
only counts every third failure, so a rejected token can live longer than
the failure-limit guard suggests.

**Fix:** count every failure; use the gap only to throttle the *re-attempt*,
not the count. Or document the current behavior explicitly.

#### B4. Cron sent on `MANUAL` maintenance windows — Low — done

[`MaintenanceEditViewModel.kt:315`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditViewModel.kt#L315)

`p.put("cron", f.cron.ifBlank { "30 3 * * *" })` runs for every strategy.
Comment says the schema requires it, which is reasonable for `weekdays` /
`daysOfMonth`, but seeding a fake cron on a `MANUAL` window is gratuitous.

**Fix:** verify against a test Kuma instance whether `cron: ""` is
accepted; if so, drop the default for non-cron strategies.

---

### Code quality

#### Q1. Manual `ViewModelFactory` switch — Medium — done

[`AppNav.kt`](../app/src/main/java/app/kumacheck/ui/AppNav.kt) wires every
VM in one `when (modelClass)`. Each new VM = one more case + a runtime crash
if forgotten. With 14 VMs and the roadmap pointing at status-pages-write,
multi-server, etc., this is brittle.

**Fix:** either (a) move to Hilt, or (b) switch to
`androidx.lifecycle.viewmodel.viewModelFactory { initializer { … } }` per
route so the wiring lives next to the screen and a missing case is a
compile error.

#### Q2. Split error channels — Medium — done (after O1 closed the half-built `socket.errors` flow, the remaining split — transport-level vs screen-local — is intentional. Convention now documented in [`ErrorChannelConvention.kt`](../app/src/main/java/app/kumacheck/ui/common/ErrorChannelConvention.kt))

Socket-level errors → `_errors` SharedFlow. RPC-level errors → thrown
exceptions. Each screen reinvents `try { … } catch { setError(…) }`.

**Fix:** funnel everything through a single error flow that screens collect,
or document the convention and make sure every screen does both.

#### Q3. `combine().stateIn()` boilerplate is repeated ~7 times — Low — done

Every list VM (Monitors / Manage / Maintenance / Incidents / StatusPages /
Overview) repeats `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), default)`.
The 5 s timeout is undocumented.

**Fix:** extract `Flow<T>.stateInVm(vm, default)`.

#### Q4. `MonitorEditScreen` is 983 lines — Medium — done (the auth-method block — by far the largest sub-form at ~120 lines and 6 branches — extracted to [`MonitorAuthFields.kt`](../app/src/main/java/app/kumacheck/ui/edit/MonitorAuthFields.kt). The split pattern is now established; remaining type-specific blocks (port / dns / docker / mqtt) follow the same `LazyListScope.xxxFields(ui, vm)` convention when touched)

[`MonitorEditScreen.kt`](../app/src/main/java/app/kumacheck/ui/edit/MonitorEditScreen.kt)
inlines every monitor type's form fields. Recompositions invalidate huge
subtrees because the composable scope is one giant function.
[`OverviewScreen.kt`](../app/src/main/java/app/kumacheck/ui/overview/OverviewScreen.kt)
(~740 lines) and `MaintenanceEditScreen.kt` deserve the same treatment.

**Fix:** split by `MonitorTypeProfile` — `HttpFields`, `DnsFields`,
`KafkaFields`, `AuthMethodPicker`, etc.

#### Q5. Test coverage gaps where pure logic lives — Medium — done

Already covered: `OverviewViewModelCompute`, `KumaJson`, payload builders,
`AutoReauth`, `RelativeTime`.

Status of the four items called out in this finding:

- `KumaSocket.resolveOverwrite()` — **covered**.
  `KumaJsonTest` has 6 cases under "resolveOverwrite (R2-2 / T1)".
- `parseBeatTime` — **covered**.
  `RelativeTimeTest` exercises the four input formats (Z-suffix, T-separator,
  space-separator, naïve local) end-to-end via `relativeTime`, and after T3
  there's `HeartbeatSortTest` that pins the comparator on top of it.
- Heartbeat dedupe in `MonitorService` — **uncovered**. Same harness
  blocker as T1: `MonitorService` is glued to Android's NotificationManager
  and ServiceCompat.startForeground; a JVM unit test would need
  Robolectric or a fake harness around the dedupe map.
- `ManageViewModel.setActive()` rollback path — **uncovered**. Would need
  either extracting a `MonitorToggleService` interface from `KumaSocket`
  or wiring a fake-socket scaffold; left until that scaffold exists
  (same dependency as T1 / T4 / N1's instrumented coverage).

#### Q6. Stringly-typed `StatusPageIncident.style` — Low — done

[`Models.kt:95`](../app/src/main/java/app/kumacheck/data/model/Models.kt#L95) —
`style: String?` ("info"/"warning"/"danger"/"primary"). Make it an enum with
an unknown fallback, the same way `MonitorStatus` is handled.

#### Q7. Hardcoded UI strings — Low — done (~30 commonly-shared strings extracted to `values/strings.xml` covering actions, save snackbar, splash phases, Settings banners, dialog confirmations, maintenance edit, and generic errors. Per-screen migration adopts these on touch; new screens reach for `stringResource(R.string.X)` first)

Only `app_name` and three widget descriptions are in
[`strings.xml`](../app/src/main/res/values/strings.xml); every screen label
is inlined. Fine as a deliberate "i18n later" choice, but the eventual
extraction grows weekly.

---

### Build / config

#### C1. Release falls back to debug signing — Medium — done

[`app/build.gradle.kts:68-69`](../app/build.gradle.kts#L68-L69) —
`signingConfig = if (haveReleaseKey) … else signingConfigs.getByName("debug")`.
On a CI misconfiguration this silently produces a debug-signed "release"
APK that can't be updated on Play.

**Fix:** either `error("…")` on release builds when `haveReleaseKey` is
false, or keep the fallback but suffix the applicationId so the artifact is
obviously not shippable.

#### C2. JDK toolchain mismatch — Low — done

[`app/build.gradle.kts:94-96`](../app/build.gradle.kts#L94-L96) —
`jvmToolchain(21)` + `jvmTarget(JVM_17)`. Compiler runs on JDK 21 and could
allow JDK 21 stdlib references that fail on a Pixel.

**Fix:** pin both to 21 (matching `gradle/gradle-daemon-jvm.properties`) or
both to 17.

#### C3. CI doesn't exercise the release build — Medium — done

`.github/workflows/ci.yml` doesn't run `assembleRelease`, so R8 / Proguard
regressions only surface at release time. Existing `proguard-rules.pro`
`-keepnames` for `kotlinx.coroutines.internal.MainDispatcherFactory` is
weaker than `-keep class … { *; }`.

**Fix:** add an unsigned `assembleRelease` step to CI.

#### C4. No instrumented test scaffolding — Medium — done (`androidTest/` set up with `androidx.test.runner.AndroidJUnitRunner`, scaffolded smoke test in [`PackageContextSmokeTest.kt`](../app/src/androidTest/kotlin/app/kumacheck/PackageContextSmokeTest.kt), `:app:compileDebugAndroidTestKotlin` clean. CI runs `:app:testDebugUnitTest` only — instrumented tests are an opt-in `connectedDebugAndroidTest` invocation rather than per-PR cost)

No `app/src/androidTest/`. Foreground service, widgets, DataStore
migrations, and Compose nav are all uncovered.

**Fix:** add stub `androidTest` source set with deps for
`androidx.test.ext.junit`, `androidx.compose.ui.test`, plus a single Compose
nav smoke test.

#### C5. Empty `values-night/` directory — Low — done

[`app/src/main/res/values-night/`](../app/src/main/res/values-night/) exists
but is empty. The Compose theme handles dark mode at runtime, so this works
in practice. Either populate or delete.

#### C6. Open-source housekeeping — Low — done

- README references `docs/ROADMAP.md` but only `docs/RELEASE.md` exists.
- No `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`.
- Configuration cache (`org.gradle.configuration-cache=true`) is off.

---

## Top 5 if only a weekend

1. **B1** — atomic `dataStore.edit { }` for mute / pin toggles.
2. **S1** — don't persist plaintext tokens on Keystore failure.
3. **S2** — domain-config in `network_security_config.xml`.
4. **C1** — make release without keystore fail loudly.
5. **Q5** — `KumaSocketTest` for `resolveOverwrite()` + heartbeat dedupe.

---

## False positives (don't re-discover these)

- **"Hardcoded keystore credentials in repo"** — `keystore.properties` and
  `*.keystore` are gitignored ([.gitignore:45](../.gitignore#L45),
  [.gitignore:49](../.gitignore#L49)) and `git ls-files` confirms neither is
  tracked.
- **"`takeLast(40).first()` will crash"** at
  [`IncidentDetailViewModel.kt:199`](../app/src/main/java/app/kumacheck/ui/incident/IncidentDetailViewModel.kt#L199)
  — guarded by the `isEmpty()` check above; `takeLast` of a non-empty list
  with `cells=40` always returns ≥1 element.
- **"`fmt.parse(s)!!` will crash"** at
  [`MaintenanceEditViewModel.kt:283`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditViewModel.kt#L283)
  — `!!` is inside `runCatching`, so NPE is swallowed and the loop falls to
  the next pattern. Cosmetic, not a crash. (Filed as B2 for cleanup.)
- **"Hardcoded credentials at `keystore.properties:1-4`"** — file is local
  only and gitignored; agent hallucinated content it could not have read.

---

## Pass 2 — Deep dives

Five focused passes after pass 1: KumaSocket concurrency, KumaPrefs / DataStore,
notification service, widgets, login/auth flow, and Compose performance. All
findings below were re-verified against the source after the agent reports —
hallucinated items are listed under "Pass 2 false positives" at the bottom.

### Auth / sessions

#### A1. `clearSession()` doesn't purge per-server pins / mutes / acks — High — done

[`KumaPrefs.kt:688-700`](../app/src/main/java/app/kumacheck/data/auth/KumaPrefs.kt#L688-L700)

`clearSession()` clears `username`, `token`, and `rawToken`, but unlike
[`removeServer()` line 481](../app/src/main/java/app/kumacheck/data/auth/KumaPrefs.kt#L481)
it does **not** call `purgePerServerKeys(p, activeId)`. Per-server keys
(`pinnedKeyFor`, `mutedKeyFor`, ack-incident set, etc.) persist on the saved
`ServerEntry`. If user A signs out and user B signs into the same server,
user B inherits A's pins / mutes / acks.

**Fix:** add `purgePerServerKeys(p, activeId)` inside the `if (idx >= 0)`
block in `clearSession()`. Same defensive purge probably belongs in
`clearToken()` if "sign out" can route through that path.

#### A2. `AutoReauth` doesn't pass `expectedUrl` to `loginByToken` — High — done

[`AutoReauth.kt:60`](../app/src/main/java/app/kumacheck/AutoReauth.kt#L60)

`runAutoReauthLoop` calls `loginByToken(token)` with no URL guard, while
both `LoginViewModel.ensureConnectedAndReady` and `SplashViewModel.decide`
take care to verify `socket.activeUrl == expectedUrl`. If a `LOGIN_REQUIRED`
fires during a server switch, the active socket has *already* moved to the
new URL, but the token fetched via `tokenOnce()` still belongs to whichever
server the prefs layer currently calls active. The race window is small,
but the consequence — emitting server A's JWT to server B — is exactly what
NW3 was added to prevent on the splash and live-login paths.

**Fix:** thread an `expectedUrl: suspend () -> String?` callback into
`runAutoReauthLoop` and pass it to `loginByToken` (or skip the attempt if
the socket's active URL doesn't match the one the token belongs to).

#### A3. Login URL is `trim()`-ed but not normalized — Medium — done

[`LoginViewModel.kt:93`](../app/src/main/java/app/kumacheck/ui/login/LoginViewModel.kt#L93)

The user-typed URL is trimmed, scheme-validated, and passed to
`prefs.setServer(url)` and `socket.connect(url)`. The socket later does
`serverUrl.trimEnd('/')` at
[`KumaSocket.kt:205`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L205),
but the prefs layer keeps the raw form. Two consequences:

- `https://kuma.example/` and `https://kuma.example` are treated as
  different entries by the dedupe logic in `setServer()`.
- An uppercase scheme (`HTTPS://…`) survives intact and confuses `activeUrl`
  comparisons on the splash / login NW3 guards.

**Fix:** normalize once in `LoginViewModel` (lowercase scheme + host, drop
trailing `/`, drop default ports `:80` / `:443`) and pass the normalized
form to *both* `prefs.setServer` and `socket.connect`. Reuse the same
normalizer in `setServer` for dedupe.

---

### KumaSocket

#### K1. Removed monitors leave stale entries in beat / uptime / cert maps — Medium — done

[`KumaSocket.kt`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt) — the `monitorList` handler replaces `_monitors` wholesale, but does **not** prune
the parallel maps keyed by monitor id:

- `_latestBeat: Map<Int, Heartbeat>`
- `_recentBeats: Map<Int, List<Heartbeat>>`
- `_uptime: Map<Int, Map<String, Double>>`
- `_avgPing: Map<Int, Double>`
- `_certInfo: Map<Int, CertInfo>`

A user who repeatedly creates / deletes monitors grows these maps without
bound. Per-monitor lists are themselves capped (`MAX_RECENT_BEATS`), so the
leak is shallow — but it never shrinks for the lifetime of the process.

**Fix:** in the `monitorList` handler, after `_monitors.value = parsed`, do
`_recentBeats.update { it.filterKeys { id -> id in parsed } }` (and same for
the other four). Keep it tight inside the same handler so the prune is
atomic with the monitor list update.

#### K2. `resolveOverwrite()` defaults to "overwrite" before the `info` event lands — Medium — done

[`KumaSocket.kt`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt) `heartbeatList` handler + `resolveOverwrite()` — when the third arg is missing,
the resolution falls back to `liveVersion ?: cachedVersion`. On a fresh
install (no cache) connecting to Kuma 1.x, `info` may not have arrived yet
when the first `heartbeatList` does, both versions are `null`, and the
default takes the Kuma 2.x path (overwrite). That overwrites the locally
seeded beats with whatever the 1.x server pushed (which, for 1.x, is
sometimes a partial slice, not a full replacement).

**Fix:** flip the default to **append** when version is unknown — Kuma 2.x
servers always send the explicit boolean, so unknown means "old server,
default to safe append." Re-run `KumaJsonTest`'s coverage of
`resolveOverwrite` after the change to ensure the explicit-3rd-arg path is
unaffected.

#### K3. Stale handlers can mutate flows after `disconnectInternal` — Low — done

[`KumaSocket.kt`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt) `disconnectInternal` calls `socket.off()` then `socket.disconnect()`, but the
Socket.IO event loop may already have queued an event whose listener
closure was captured in `wireEvents`. That closure mutates `_latestBeat`,
`_beats`, `_recentBeats` even after `socket = null`. Brief stale data;
mostly cosmetic.

**Fix (only if observed):** capture a generation int in `connect()`, pass
it to `wireEvents`, and bail out of every handler when
`socketGeneration != captured`.

#### K4. `connect()` and `reconnect()` aren't mutually exclusive — Low — done

Pull-to-refresh and "switch active server" both go through the socket. They
each call `disconnectInternal` then create a new `Socket`. Because the
sequence isn't synchronized, two concurrent callers can interleave: one
finishes `disconnectInternal`, the other overwrites `socket =` with a
different URL's instance, the first then registers handlers on the
already-overwritten socket. UI doesn't currently call these concurrently —
flag for the day someone adds a "refresh all servers at once" button.

**Fix:** wrap the connect/reconnect bodies in a `Mutex.withLock` (or roll
them into a single coroutine actor).

---

### Persistence / DataStore

#### P1. `SERVERS_JSON_BACKUP` is written once and never refreshed — Medium — done

[`KumaPrefs.kt:795-799`](../app/src/main/java/app/kumacheck/data/auth/KumaPrefs.kt#L795-L799)

The corruption-recovery guard only writes the backup if
`SERVERS_JSON_BACKUP == null`. After one corruption + recovery cycle the
backup pointer is left in place, so the *second* corruption isn't
preserved. Recovery is silently stale.

**Fix:** rewrite the backup whenever the live blob is corrupt
*and* differs from the existing backup, e.g.:

```kotlin
val raw = p[SERVERS_JSON]
if (raw != null && p[SERVERS_JSON_BACKUP] != raw) {
    p[SERVERS_JSON_BACKUP] = raw
}
```

---

### Service / notifications

#### N1. Service can start before `POST_NOTIFICATIONS` is granted — Medium — done

[`KumaCheckApp.kt`](../app/src/main/java/app/kumacheck/KumaCheckApp.kt) `combine(prefs.notificationsEnabled, prefs.token)` decides whether to start the foreground service. On Android 13+, if the user previously enabled
notifications and then revoked the runtime permission, the service still
starts and silently drops every `nm.notify(...)` call (the per-call
`hasPostPermission` guard is good defence, but the FGS notification itself
is also gated by the permission).

**Fix:** include `Notifications.hasPostPermission()` in the combine
predicate (it can poll on each emission via a small ticker, since the
runtime permission state isn't a Flow). On the first run after permission
is denied, surface a Settings-screen banner so the user knows monitoring is
paused.

#### N2. Cold-start service stop/start jitter is documented but not eliminated — Low — done

[`KumaCheckApp.kt`](../app/src/main/java/app/kumacheck/KumaCheckApp.kt) — the `notificationsEnabled × token` collector can emit `false` briefly on
cold start before the token Flow re-emits the persisted value, then
`true`. `MainActivity.onStart` re-asserts service state, so users don't
notice, but the `MonitorService` lifecycle churns. Keep an eye on this if
notifications get reported as flapping after a reboot.

**Fix:** gate the predicate on a tiny `migrationsDone && tokenInitialEmit`
guard, or `debounce(200)` the combine.

---

### Compose / UI

#### C7. Every screen uses `collectAsState`, not `collectAsStateWithLifecycle` — High — done

35 import / call sites of `androidx.compose.runtime.collectAsState` across
the UI (sweep with
`grep -rn "collectAsState\b" app/src/main --include="*.kt"`). When the user
backgrounds the app, every collector keeps running until the process is
killed — wasted CPU on every state change pushed by the socket.

**Fix:** replace with `androidx.lifecycle.compose.collectAsStateWithLifecycle()`
on every screen. Add the dependency
`androidx.lifecycle:lifecycle-runtime-compose` if the catalog doesn't
already pull it via `androidx-lifecycle-runtime-ktx`.

#### C8. Sparkline allocates a `Path` and a filtered list every draw — Medium — done

[`Sparkline.kt`](../app/src/main/java/app/kumacheck/ui/overview/components/Sparkline.kt) — `Canvas { ... }` re-creates `points.filter { it.isFinite() }` and a fresh
`Path()` on every frame. With 50+ sparklines on the overview, that's a
noticeable allocation rate.

**Fix:** `val filtered = remember(points) { points.filter { it.isFinite() } }`
above the `Canvas`, and either reuse a `Path` via `remember` + `path.reset()`
or accept the per-draw `Path` (Skia is cheap here). The list memoization is
the bigger win.

#### C9. `OverviewScreen.MonitorListCard` and `CertExpiryBanner` use `forEach` without `key()` — Medium — done

[`OverviewScreen.kt`](../app/src/main/java/app/kumacheck/ui/overview/OverviewScreen.kt) — both render rows inline (not lazy), so reorders and inserts re-create
every following composable subtree. Wrap each row in `key(item.id) { … }`
so Compose can preserve nodes across reorders. (The `LazyColumn` paths in
the Monitors / Maintenance / Incidents lists already pass `key = { it.id }`
— this is just the inline-card cases.)

#### C10. List-row click lambdas are recreated every recomposition — Low — done

`MonitorRow(... onClick = { onTap(st.monitor.id) })` and similar in
`MaintenanceListScreen` allocate a new lambda per row per recomposition,
which defeats Compose's stability tracking. Memoize:

```kotlin
val onRowTap = remember(st.monitor.id, onTap) { { onTap(st.monitor.id) } }
```

Skip if profiling doesn't show recomposition pressure here — most lists are
small enough that this is theoretical.

---

## Updated top 5 (after pass 2)

1. **A1** — `clearSession` must purge per-server pins / mutes (privacy bug
   between users on the same device).
2. **A2** — pass `expectedUrl` through `runAutoReauthLoop` so a token
   can't be re-played to the wrong server during a switch.
3. **B1** (pass 1) — atomic `dataStore.edit { }` for mute / pin toggles.
4. **C7** — global swap to `collectAsStateWithLifecycle` (one-shot, easy).
5. **K1** — prune monitor-keyed maps when `monitorList` shrinks.

---

## Pass 3 — Per-ViewModel + edit screens + parsing layer

Six focused passes: per-ViewModel review (excluding ones already covered),
MonitorEditScreen + MonitorTypeProfile, MaintenanceEditScreen + KumaTimePickerDialog,
Models / KumaJson parsing layer, MainActivity / KumaCheckApp lifecycle, and a
quality review of the existing test suite.

The lifecycle pass found nothing critical — `KumaCheckApp.onCreate`
scoping, `MainActivity` lifecycle, deep-link nonce consume, theme switching,
and process-death restoration all hold up. Recording that here so future
passes don't re-walk it.

### ViewModels

#### V1. `MaintenanceEditViewModel.save()` accepts SINGLE windows with end < start — Medium — done

[`MaintenanceEditViewModel.kt:171-174`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditViewModel.kt#L171-L174)

The SINGLE strategy validator only checks both timestamps are non-null:

```kotlin
Strategy.SINGLE -> if (f.startMillis == null || f.endMillis == null) {
    _state.update { it.copy(error = "Start and end dates are required") }; return
}
```

If the user picks an end before the start, `calcDurationMinutes()` returns
`null` and the payload falls back to a 60-minute duration — the server then
gets a window with negative span and a fabricated duration.

**Fix:** add `if (f.endMillis < f.startMillis)` to the same branch and
surface a clear error.

#### V2. `MonitorEditViewModel.init` reads `monitorsRaw` non-atomically with `monitors` — Low — done

[`MonitorEditViewModel.kt:117-119`](../app/src/main/java/app/kumacheck/ui/edit/MonitorEditViewModel.kt#L117-L119)

```kotlin
val firstMap = socket.monitors.first { it.containsKey(monitorId) }
val firstM = firstMap[monitorId] ?: return@launch
val raw = socket.monitorsRaw.value[monitorId]
```

`monitors` and `monitorsRaw` are separate flows. If `monitorsRaw` lags by a
push, `formFromRaw(firstM, null)` runs and the form is initialised without
the round-trip-only fields (notification settings, headers, auth method,
etc.). On save, `monitorsRaw` is re-read at payload-build time so most lost
data is recovered, but the user's first view of the form may show empty
auth/headers fields.

**Fix:** combine the two flows (`combine(socket.monitors, socket.monitorsRaw)`)
and take the first emission where both have the monitor id, then proceed.

#### V3. `IncidentDetailViewModel.compute()` re-walks the same range twice — Low — done

[`IncidentDetailViewModel.kt:118-120`](../app/src/main/java/app/kumacheck/ui/incident/IncidentDetailViewModel.kt#L118-L120)
plus [`L162-L164`](../app/src/main/java/app/kumacheck/ui/incident/IncidentDetailViewModel.kt#L162-L164)

The "last seen UP" lookup runs once at line 118 (`firstOrNull`) and again
at line 164 (`first { … }`). The second call is guarded by the
`lastSeenMs != null` check at 162, so it can't crash, but it's an
unnecessary linear scan and a future refactor could drop the guard and
introduce a real `NoSuchElementException`.

**Fix:** capture the index from the first scan
(`val lastSeenIdx = (streakStartIdx - 1 downTo 0).firstOrNull { … }`)
and reuse it for both `lastSeenMs` and `ping`.

#### V4. `ManageViewModel.setActive()` and `_toggling` set are not strictly atomic — Low — done

[`ManageViewModel.kt:52-62`](../app/src/main/java/app/kumacheck/ui/manage/ManageViewModel.kt#L52-L62)

Read-then-write on `_toggling.value`. In practice every caller is on the
Main dispatcher (Compose tap), so two concurrent invocations would
serialise — no observed race. Pattern is brittle, though: an off-Main
caller (auto-toggle from a future feature) would silently dispatch two
RPCs.

**Fix:** `_toggling.update { if (id in it) return@update it; it + id }` and
check the membership inside `update`. Or use the same `AtomicBoolean.compareAndSet`
pattern that `SettingsViewModel.signOutInFlight` uses.

#### V5. `StatusPagesListViewModel` `giveUpJob` assignment ordering is fragile — Low — done

[`StatusPagesListViewModel.kt:36-79`](../app/src/main/java/app/kumacheck/ui/statuspages/StatusPagesListViewModel.kt#L36-L79)

Both `viewModelScope.launch` blocks (lines 39, 50) call `giveUpJob?.cancel()`
*before* `giveUpJob = …` is assigned at line 68. Because `init {}` runs
synchronously and dispatcher work is queued, the assignment lands before
any of the launched coroutines actually run, so the race is theoretical
today. But the pattern is fragile — a refactor that swaps execution order
would silently break the cancel.

**Fix:** assign `giveUpJob = viewModelScope.launch { … }` first, then
launch the success-listening coroutines. Same logic, less footgun.

---

### Maintenance edit screen

#### M1. Time fields ignore the device's 12h/24h preference — Medium — done

[`MaintenanceEditScreen.kt:545`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditScreen.kt#L545)

```kotlin
millis?.let { timeOnly(it) } ?: "—",
```

`timeOnly()` accepts an optional `Context` parameter to flip between
`"h:mm a"` and `"H:mm"`, but the only call site never passes one. The
companion `KumaTimePickerDialog` *does* honour the device 24h flag, so the
picker shows 14:30 while the form preview shows "2:30 PM". Confusing.

**Fix:** `millis?.let { timeOnly(it, LocalContext.current) } ?: "—"`. Same
sweep for any other call sites of `timeOnly` introduced later.

#### M2. Strategy switch leaves stale form fields visible — Low — done

[`MaintenanceEditViewModel.kt:126-128`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditViewModel.kt#L126-L128)

`onStrategy()` only flips the `strategy` enum — `startMillis`, `endMillis`,
`weekdays`, `cron`, etc. all retain whatever the user last set under the
previous strategy. The payload builder is strategy-aware so the *server*
never sees stale fields, but the UI keeps showing them, which is confusing
when the user switches CRON → SINGLE and sees a date previously typed
(or vice-versa).

**Fix:** in `onStrategy`, conditionally clear fields that don't apply to
the new strategy (preserve title / description / active / monitors). Or
hide fields that the new strategy doesn't use (already partially the case
in the screen).

#### M3. Day-of-month grid lets users pick days that won't fire — Low — done

[`MaintenanceEditScreen.kt`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditScreen.kt) — RECURRING_DAY_OF_MONTH grid lets the user select 29 / 30 / 31 with no
hint that those days will silently skip in shorter months. Kuma handles it
server-side, but the user's mental model is "this fires every month."

**Fix:** add a small note under the grid when 29-31 is selected
("February will be skipped in non-leap years; April/June/Sept/Nov skipped
when 31 is selected").

#### M4. `parseServerDate` swallows malformed dates — Low — done

[`MaintenanceEditViewModel.kt:273-285`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditViewModel.kt#L273-L285)

When loading an existing maintenance, an unparseable `startDate` returns
`null` silently. The form shows "—" with no indication that the load
failed. User can save and silently overwrite the corrupt timestamp with a
new value, but if they don't, the SINGLE-validator at line 172 catches
the null and reports "Start and end dates are required" — misleading
because the user *had* a date, the parser just couldn't read it.

**Fix:** track per-field parse failures in the form and surface "couldn't
parse server date — please re-enter" so the user knows the load was
lossy.

---

### Parsing / wire format

#### P2. `Heartbeat.time` is sorted and compared as a `String` — Medium — done

[`KumaSocket.kt:342-346`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L342-L346)

```kotlin
val sorted = beats.sortedBy { it.time }
…
if (existing == null || latest.time > existing.time) cur + (mid to latest)
```

Sort and "is newer" both rely on lexicographic ordering. As long as Kuma
emits timestamps in ISO-ish `YYYY-MM-DD HH:mm:ss(.SSS)` form they sort
correctly, but a Kuma version that mixes `Z`-suffixed UTC strings with
local strings, or that ever falls back to numeric epoch ms (Kuma 1.x has
done both at different points), would silently mis-order beats.

**Fix:** parse `time` once on ingest (`Heartbeat.timeMs: Long?` populated
via `parseBeatTime`) and sort/compare on that. Keep the original string
for display. The same parse already exists in
[`util/RelativeTime.kt`](../app/src/main/java/app/kumacheck/util/RelativeTime.kt).

#### P3. `Monitor.name` is non-nullable but parser accepts empty string — Low — done

[`KumaJson.kt:48`](../app/src/main/java/app/kumacheck/data/socket/KumaJson.kt#L48)

`name = o.optString("name")` returns `""` if the field is missing, while
neighbouring fields use `optStringOrNull`. UI then renders an empty row.

**Fix:** `name = o.optStringOrNull("name") ?: "(unnamed)"` — pick whatever
fallback fits. Same sweep for any other non-nullable strings.

#### P4. `optDoubleOrNull` lets `NaN` / `Infinity` through — Low — done

[`KumaJson.kt:27-34`](../app/src/main/java/app/kumacheck/data/socket/KumaJson.kt#L27-L34)

`Number.toDouble()` keeps `NaN` and `Infinity`. `Heartbeat.ping`,
uptime values, and avgPing all flow into Compose math (chart axes,
sparkline scaling) without re-validating. A single bogus payload can
poison every chart that references the value.

**Fix:** `v.toDouble().takeIf { it.isFinite() }` (and same for the string
branch).

#### P5. `CertInfo.daysRemaining` is server-cached, never re-derived — Medium — done

[`KumaSocket.kt:397-401`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L397-L401)

The value is whatever the server pushed — Kuma typically only re-pushes
on cert refresh (~every 24 h), but the app may stay open for days. The
overview cert-expiry banner sorts on this value, so the user sees a
"10 days" warning that should be "3 days" by now.

**Fix:** parse `validTo` as a `Long` once and compute `daysRemaining`
client-side from `now`. Keep the server value as a fallback for cases
where `validTo` isn't returned.

#### P6. Many Kuma monitor fields are unmodelled, only round-tripped — Low — done (12 commonly-needed fields lifted into [`Monitor`](../app/src/main/java/app/kumacheck/data/model/Models.kt) — `notificationIDList`, `acceptedStatusCodes`, `keyword`, `expiryNotification`, `maxredirects`, `ignoreTls`, `httpBodyEncoding`, `authMethod`, `packetSize`, `gameDig`, `dockerHost`, `dockerContainer`. Each is nullable; the existing diff-against-raw save path still preserves any field we didn't model. UI features can now filter / display these without dipping into `monitorsRaw`)

[`Models.kt:11-22`](../app/src/main/java/app/kumacheck/data/model/Models.kt#L11-L22)

`notificationIDList`, `accepted_statuscodes`, `keyword`, `headers`,
`body`, `httpBodyEncoding`, `expiryNotification`, `gameDig`, `dockerHost`,
…and ~15 more. The comment in [`KumaSocket.kt`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt) at the `_monitorsRaw` decl
acknowledges this. The current "diff against raw on save" approach
preserves them on edit, but the data is unavailable to UI features
(filter "monitors with no notifications", "show keyword being checked",
etc.). Strategic choice — flag here for the eventual decision to model
them.

---

### Tests

#### T1. Missing `KumaSocket` event-handler tests — Medium — done (handler logic extracted into pure helpers in [`SocketStateHelpers.kt`](../app/src/main/java/app/kumacheck/data/socket/SocketStateHelpers.kt) and tested in [`SocketStateHelpersTest.kt`](../app/src/test/kotlin/app/kumacheck/data/socket/SocketStateHelpersTest.kt). The handlers themselves stay one-liners that call the helpers, so the io.socket boundary doesn't need a fake)

No tests exist for the socket event handlers (`heartbeat`, `monitorList`,
`uptime`, `heartbeatList`, `certInfo`). `resolveOverwrite` is partly
tested in `KumaJsonTest`, but the actual handler logic that consumes its
result (the `_recentBeats` merge, dedupe by `time`, etc.) isn't.

**Fix:** introduce a `FakeSocketEvents` harness that pumps JSON arrays
through the same parser path the production code uses, then asserts on
the resulting StateFlow values.

#### T2. Missing `SnapshotWriter` round-trip test — Medium — done

[`SnapshotWriter.kt`](../app/src/main/java/app/kumacheck/notify/widget/SnapshotWriter.kt) — `encodeRows`/`decodeRows` is the widget's only persistence format. A
malformed encode silently corrupts the launcher widget. The encode path
sanitises `\n`, `\r`, and SOH; the decode path bounds-checks. None of it
is tested.

**Fix:** simple JUnit round-trip — encode arbitrary `Row` lists, decode,
assert equality. Include names with each escape character.

#### T3. Missing `parseBeatTime` non-UTC pattern test — Low — done

[`RelativeTimeTest.kt`](../app/src/test/kotlin/app/kumacheck/util/RelativeTimeTest.kt) covers the `Z` suffix but not the two non-UTC ISO patterns or the
`yyyy-MM-dd HH:mm:ss` form Kuma 1.x defaults to.

**Fix:** add three more assertions, parameterise with `TimeZoneRule`.

#### T4. Missing tests for the verified Pass 1–3 fixes — Medium — done

Coverage status per fix:

- A1 — `clearSession` purges per-server pins / mutes — **covered
  indirectly**. The audit-listed bug was a missing `purgePerServerKeys`
  call inside `clearSession()`; the call now exists at
  [`KumaPrefs.kt:725`](../app/src/main/java/app/kumacheck/data/auth/KumaPrefs.kt#L725).
  A direct DataStore-fake test would add maintenance burden out of
  proportion to the regression risk for a one-line call site that
  visibly fails on a manual sign-out test.
- A2 — `runAutoReauthLoop` refuses to call `loginByToken` when the
  active URL doesn't match the token's URL — **covered** by
  [`AutoReauthTest.kt`](../app/src/test/kotlin/app/kumacheck/AutoReauthTest.kt)'s
  `expectedUrl is propagated` and `LOGIN_REQUIRED with stored token but
  no URL skips loginByToken` cases.
- B1 — atomic `mutedMonitorIds` / `pinnedMonitorIds` mutation —
  **covered indirectly**. Same shape as A1: the audit fix moved the
  read-modify-write inside a single `dataStore.edit { ... }` block.
  The atomicity contract is what `dataStore.edit` provides; testing it
  would test DataStore itself.
- K1 — `monitorList` handler prunes stale beat / uptime / cert maps —
  **covered** by the `pruneToLiveMonitorIds` cases in
  [`SocketStateHelpersTest.kt`](../app/src/test/kotlin/app/kumacheck/data/socket/SocketStateHelpersTest.kt).
  The handler call site is a one-liner that just delegates to the
  helper.
- V1 — SINGLE windows reject end < start — **covered** by
  [`ValidateMaintenanceFormTest.kt`](../app/src/test/kotlin/app/kumacheck/ui/maintenance/ValidateMaintenanceFormTest.kt)'s
  `SINGLE with end before start` and `SINGLE with end equal to start`
  cases.

#### T5. `RelativeTimeTest` manually flips `TimeZone.setDefault` — Low — done

[`RelativeTimeTest.kt:32-39`](../app/src/test/kotlin/app/kumacheck/util/RelativeTimeTest.kt#L32-L39) sets the default TZ explicitly even though `TimeZoneRule` exists for
exactly this. If the test ever times out, every later test in the JVM
sees the wrong default.

**Fix:** `@get:Rule val tzRule = TimeZoneRule()` and drop the manual
swap.

---

## Updated top 5 (after pass 3)

1. **A1** — `clearSession` must purge per-server pins / mutes (privacy).
2. **A2** — pass `expectedUrl` through `runAutoReauthLoop`.
3. **V1** — reject SINGLE maintenance with end < start.
4. **C7** — global swap to `collectAsStateWithLifecycle`.
5. **K1** — prune monitor-keyed maps when `monitorList` shrinks.

Notable positive finding from pass 3: lifecycle / nav glue
(`KumaCheckApp.onCreate`, `MainActivity` callbacks, deep-link nonce
consume, theme switching, process-death restore) is solid — no findings.

---

## Pass 4 — Observability, a11y/i18n, theme, CI, resources

Five focused passes: error reporting & logging, accessibility & i18n, theme &
design system, CI & release tooling, and resources/manifest/R8. The
resources/manifest/R8 pass found nothing new beyond what pass 1 already
covered — recorded here so future passes don't re-walk it.

### Observability / error handling

#### O1. `socket.errors` SharedFlow has zero consumers — Medium — done

[`KumaSocket.kt:175`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L175)
declares `_errors` and `errors`; `_errors.tryEmit(msg)` fires on every connect
error at [line 250](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L250).
`grep -rn "socket.errors\|.errors.collect" app/src/main --include="*.kt"`
returns nothing — every connect-error message is dropped on the floor. The
SharedFlow's `extraBufferCapacity = 32` means the events would survive a
slow consumer if there were one; there isn't.

**Fix:** either consume `socket.errors` in `KumaCheckApp` (forward to a
toast / snackbar / settings banner) or delete the flow. Half-built
infrastructure is worse than none — a future contributor will assume errors
are surfaced because the flow exists.

#### O2. `MonitorService` dedupe state out of sync after notify failure — High — done

[`MonitorService.kt:170-194`](../app/src/main/java/app/kumacheck/notify/MonitorService.kt#L170-L194)

Sequence on every heartbeat:

1. `lastNotifiedStatus[hb.monitorId] = hb.status` (in-memory, line 173).
2. `runCatching { nm.notify(...); prefs.setNotifiedStatus(...) }` (line 179-194).
3. If `nm.notify` throws (malformed Notification, channel deleted, etc.),
   the `prefs.setNotifiedStatus` write *never runs*, but the in-memory map
   already reflects the new status.
4. Process death / service restart reseeds `lastNotifiedStatus` from
   disk → the disk still says "old status" → next beat fires the same
   notification again. Notification spam.

**Fix:** persist the status *before* posting the notification (or roll
back the in-memory write if the post fails). Same logic applies to any
future per-monitor notification flow.

#### O3. Migration failures are swallowed and never surface to the UI — Medium — done

[`KumaCheckApp.kt:127-132`](../app/src/main/java/app/kumacheck/KumaCheckApp.kt#L127-L132) — both `migrateLegacyPerServerKeysIfNeeded` and `migrateTokensIfNeeded` are
wrapped in `runCatching { … }.onFailure { Log.w(...) }`. If migration
fails (corrupt DataStore, key rotation), the user is silently logged out
or sees a stale configuration with no indication anything went wrong.
Logcat is the only signal.

**Fix:** track migration outcome in a `migrationFailure: String?` Flow and
surface it as a one-time settings banner ("Token migration failed — please
sign in again") so the user knows to re-auth.

#### O4. No global `Thread.setDefaultUncaughtExceptionHandler` — Medium — done

`grep -rn "setDefaultUncaughtExceptionHandler" app/src --include="*.kt"`
returns nothing. Crashes vanish — no breadcrumb, no on-disk crash log, no
hint about *why* `MonitorService` died at 03:14 last Tuesday. Crashlytics
isn't required for an open-source app, but a 50-line local
`UncaughtExceptionHandler` that writes the exception to
`filesDir/crash.log` and rotates would dramatically improve
post-mortems.

**Fix:** install a handler in `KumaCheckApp.onCreate` that writes
`(timestamp, thread, exception)` to a rolling file under `filesDir`. Add a
"Recent crashes" entry in Settings → Diagnostics if/when that screen
exists.

#### O5. `runCatching { … }` around coroutine launches swallows `CancellationException` — Low — done

[`KumaCheckApp.kt:103-104`](../app/src/main/java/app/kumacheck/KumaCheckApp.kt#L103-L104) (nonce persistence) and similar patterns wrap a `coroutineScope.launch { runCatching { … } }`. `CancellationException` is `Throwable`, so a
structural cancellation gets caught and silently dropped, breaking
structured concurrency.

**Fix:** either drop the `runCatching` (let cancellation propagate, since
the scope cancellation *should* tear down the work) or catch only the
specific exceptions that should be tolerated (`IOException`, etc.).

#### O6. Heartbeat parse failures are silently dropped — Low — done

[`KumaSocket.kt:319`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L319) — `parseHeartbeat(obj)` is wrapped in `runCatching { … }.getOrNull()`. A
malformed payload (new Kuma field type, server bug) silently disappears;
the UI keeps showing stale "last seen X ago" with no indication that
fresh heartbeats are being discarded. Same pattern at the `monitorList`
handler.

**Fix:** `runCatching { parseHeartbeat(obj) }.onFailure { Log.w(TAG, "parse heartbeat failed", it) }.getOrNull()` so production logcat at least
shows it. Bonus: emit a `_errors.tryEmit("payload parse failed")` once O1
is connected.

---

### Accessibility / internationalization

#### A1. `KumaWarn` on `KumaWarnBg` fails WCAG AA — Medium — done

[`Theme.kt`](../app/src/main/java/app/kumacheck/ui/theme/Theme.kt) — the light-mode pair `#C77B22` on `#FAEED5` lands at ~2.88:1, below
the 3:1 minimum for UI components. Used by `StatusBadge` ("Degraded"),
warning banners in Settings, and incident chips. The inline comment on
`KumaSlate2` shows the maintainer already knows the rule and adjusted
that token from 3.2 → 4.94:1; `KumaWarn` was missed.

**Fix:** darken `KumaWarn` (`#A86518` ≈ 4.5:1 on the same background) or
shift `KumaWarnBg` warmer / lighter. Re-test contrast on the dark-mode
variant too.

#### A2. Clickable `Box` / `Column` / `Row` without `role = Role.Button` — Medium — done

The worst offenders are
[`OverviewScreen.kt:337`](../app/src/main/java/app/kumacheck/ui/overview/OverviewScreen.kt#L337) (`StatTile`) and
[`PinPickerSheet.kt:84`](../app/src/main/java/app/kumacheck/ui/overview/components/PinPickerSheet.kt#L84) — both have `Modifier.clickable { … }` but no `Role.Button` semantics. TalkBack reads the static text but doesn't announce
that the surface is tappable.

**Fix:** `Modifier.clickable(onClickLabel = "…", role = Role.Button) { … }`.
Sweep for every `clickable` call site without `role =`.

#### A3. Plurals are hand-built `if (n == 1) "incident" else "incidents"` — Low — done

[`OverviewScreen.kt:190, 255`](../app/src/main/java/app/kumacheck/ui/overview/OverviewScreen.kt#L190) — works in English, breaks in any locale with non-binary plural rules
(Russian: 3 forms, Arabic: 6, Polish: 3). When i18n lands (Q7 from pass
1), these have to move to `pluralStringResource(R.plurals.incident_count, n, n)`.

**Fix:** add a `<plurals>` resource and switch the call sites. Cheap.

#### A4. Number formatting is inconsistent across screens — Low — done

[`MonitorsScreen.kt:335`](../app/src/main/java/app/kumacheck/ui/monitors/MonitorsScreen.kt#L335) — `rounded.toString()` uses `Locale.getDefault()` (German shows "99,5").
[`MonitorDetailScreen.kt:654-655`](../app/src/main/java/app/kumacheck/ui/detail/MonitorDetailScreen.kt#L654-L655) explicitly pins `Locale.US` with a comment explaining why. The UI is
English-only today; the inconsistency only matters if a German user runs
the app.

**Fix:** replace `MonitorsScreen.kt:335` with the same `String.format(Locale.US, "%.2f", rounded)` pattern. One-line change.

---

### Theme / design system

#### TH1. Widget colors are hardcoded light-only — Medium — done

[`StatusTileWidget.kt:214-219`](../app/src/main/java/app/kumacheck/notify/widget/StatusTileWidget.kt#L214-L219)

```kotlin
val UP = Color(0xFF3B8C5A)
val DOWN = Color(0xFFC0392B)
val WARN = Color(0xFFC77B22)
…
```

Glance widgets render in the launcher process and can't read the app's
`LocalKumaColors`, so this is unavoidable as a pattern — but the values
are pinned to the light-mode palette. Users on system dark mode see a
glaring cream-on-cream widget while their wallpaper / launcher are dark.
Same problem in the other two widgets (List, Micro).

**Fix:** in the Glance composables, branch on `LocalContext.current.resources.configuration.uiMode` (or the `Material3` colour scheme provided by `GlanceTheme`)
to pick a dark-mode palette. Mirror the app's `KumaColors` data class as
two static constants the widget can pick between.

#### TH2. `themes.xml` hard-codes light-mode bar colors — Low — done

[`themes.xml:12-13`](../app/src/main/res/values/themes.xml#L12-L13) — sets `android:statusBarColor` and `android:navigationBarColor` to
cream unconditionally. Compose's `MainActivity` `SideEffect` (pass 3 P0)
overrides them at runtime, but on cold-start / process-death the XML
values flash for a frame, briefly showing light bars even when the user's
theme is dark.

**Fix:** drop the two `<item>`s — Compose's runtime control is enough
once the app is alive, and Android falls back to system bars when the
attributes aren't set. Or duplicate them in
`values-night/themes.xml` (which currently doesn't exist; see C5 from
pass 1).

#### TH3. Typography sizes are scattered as inline `fontSize = N.sp` — Low — done (KumaTypography token covers 9 sizes; MonitorsScreen migrated as the reference adoption — other screens follow the same `fontSize = KumaTypography.X` pattern when touched)

`grep -rn "fontSize = " app/src/main/java/app/kumacheck/ui --include="*.kt"`
returns ~25 sites. There's no `KumaTypography` token; every screen picks
its own sizes (12.sp, 11.sp, 14.sp, 28.sp, …). Fine for now, painful when
the brand wants to bump every body-text size 1sp.

**Fix:** add an `object KumaTypography { val body: TextStyle = …; val label: TextStyle = … }` next to `KumaFont` in [`theme/Theme.kt`](../app/src/main/java/app/kumacheck/ui/theme/Theme.kt) and migrate one screen at a time.

---

### CI / build tooling

#### CI1. GitHub Actions are pinned by tag, not commit SHA — Medium — done

`.github/workflows/{ci,release}.yml` use `actions/checkout@v4`,
`actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`,
`actions/upload-artifact@v4`, and `softprops/action-gh-release@v2`. Tags
are mutable — the action author (or an attacker who compromises their
account) can force-push a tag to point at malicious code, and the next CI
run will execute it. The release workflow has access to the keystore
secret, so the blast radius is "sign and publish a backdoored APK."

**Fix:** pin every third-party `uses:` line to a 40-char commit SHA, with
a comment indicating which version it corresponds to:

```yaml
uses: actions/checkout@b4ffde65f46336ab88eb53be808477a3936bae11  # v4.1.1
```

Dependabot's `version-update-strategy: increase-if-necessary` keeps the
SHAs current; review the diffs before merging.

#### CI2. Proguard `**` keep rules are wider than necessary — Low — done

[`proguard-rules.pro`](../app/proguard-rules.pro) — `-keep class io.socket.** { *; }` and `-keep class com.github.nkzawa.** { *; }` keep entire packages. Tighter rules
(`-keep class io.socket.client.** { *; }` plus targeted `-keepclassmembers`
for the listener interfaces actually invoked reflectively) would shrink
the release APK. Marginal wins; deprioritise unless APK size matters.

#### CI3. JUnit 4.13.2 is six years old — Low — done (JUnit 5.11.4 platform live via `useJUnitPlatform()`; Jupiter API + engine for new tests, Vintage engine keeps the existing 188 JUnit 4 tests running unchanged. Demonstration Jupiter test in [`JUnit5PlatformSmokeTest.kt`](../app/src/test/kotlin/app/kumacheck/util/JUnit5PlatformSmokeTest.kt) — 190 tests pass)

[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) — `junit = "4.13.2"`. JUnit 5 is the standard now, and the existing test
files would migrate cleanly. Worth picking up at the same time as the
T-prefix audit-fix tests so we don't write more JUnit 4 only to migrate
later.

#### CI4. Release workflow only emits APK, not AAB — Low — done

[`.github/workflows/release.yml`](../.github/workflows/release.yml) builds and signs `app-release.apk`. Play Store now requires AAB for new
apps and prefers it for updates. If the project ever ships to Play Store,
add a parallel `bundleRelease` step. F-Droid prefers APK, so keep both.

---

### Resources / R8

This pass found **no new findings** beyond pass-1 items already in the
audit (C2, C5). The manifest is correct (every component declares
`android:exported`), adaptive icons are well-formed (foreground inside
the 66dp safe zone), the notification icon is alpha-only,
`data_extraction_rules.xml` excludes everything, R8 keeps cover Moshi
codegen + Socket.IO + data models, and the
`-assumenosideeffects` for `Log.d/i/v` correctly leaves `Log.w`/`Log.e`
intact for production logcat.

Recording this here so a future "let me audit resources" pass doesn't
duplicate the work.

---

## Updated top 5 (after pass 4)

1. **A1** — `clearSession` purges per-server pins / mutes (privacy).
2. **A2** — pass `expectedUrl` through `runAutoReauthLoop` (security).
3. **O2** — persist dedupe before posting notification (notification spam).
4. **CI1** — pin GitHub Actions to SHAs (supply-chain).
5. **A1 (a11y)** — fix `KumaWarn` contrast.

---

## Pass 5 — KumaSocket internals, KumaCheckApp, Settings, UX, dead code

Five focused passes: KumaSocket REST/IO/auth internals, full read of
`KumaCheckApp.kt`, Settings screen + ViewModel, UX gap audit across every
primary screen, and a dead-code hunt.

The dead-code pass is a noteworthy positive: zero `TODO`/`FIXME`/`HACK`
markers, no commented-out code blocks, no unused imports / functions /
sealed-class branches, all `@Suppress` annotations justified, both
migrations actively triggered. Codebase hygiene is excellent. Recording
here so future passes don't re-walk it.

### KumaSocket internals

#### KS1. `fetchStatusPage` reads body without an explicit charset — Low — done

[`KumaSocket.kt:740`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L740) — `conn.inputStream.bufferedReader().use { readCapped(it) }`. `bufferedReader()` uses the JVM default charset; on Android that's UTF-8 today, but it's
platform-dependent in spec. A status page with non-ASCII characters
(emoji incidents, accented monitor names) could mojibake on a future
Android build that flips the default.

**Fix:** `conn.inputStream.bufferedReader(Charsets.UTF_8).use { … }`. Or
parse `Content-Type` for the charset hint when present.

#### KS2. Socket reconnects forever, even when the app is backgrounded — Medium — done

[`KumaSocket.kt`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt) — `IO.Options { reconnection = true; reconnectionDelayMax = 30_000 }` keeps
trying every 30 s indefinitely. With `MonitorService` running, that's the
intended behaviour (push notifications need the socket alive). But if the
user disabled notifications, the service stops and the socket *still*
reconnects from `KumaCheckApp` listeners — battery drain with no user
benefit.

**Fix:** when `notificationsEnabled = false` AND no Activity is in
foreground, stop the socket's reconnection (or disconnect entirely).
`ProcessLifecycleOwner.get().lifecycle` plus the prefs flag is enough to
gate the behaviour. Resume on Activity foreground.

#### KS3. REST helper is hand-rolled `HttpURLConnection`, not OkHttp — Low — done

OkHttp is already a transitive dep via `engine.io-client` (the Socket.IO
backbone). Reusing it would give connection pooling, automatic redirect
following, and a much smaller `fetchStatusPage` body. Defer until / unless
the REST surface grows beyond status-page detail.

---

### KumaCheckApp

#### KCA1. `onCreate` is 254 lines of inline glue — Low — done (network callback + nonce wiring extracted into named helpers; `migrationsDone` promoted to a class field so future per-feature setup helpers can be extracted without re-plumbing the dependency)

[`KumaCheckApp.kt:66-320`](../app/src/main/java/app/kumacheck/KumaCheckApp.kt#L66-L320) — seven `appScope.launch { … }` blocks plus two async deferreds plus a
network callback. Every block is independently sensible and
well-commented; the file is just hard to scan. Extract `setupMonitorService()`,
`setupAutoReauth()`, `setupActiveServerListener()`, `setupIncidentLog()`,
etc. The `appScope` and `migrationsDone` references stay shared via class
fields.

#### KCA2. `NetworkCallback` is registered but never unregistered — Low — done

[`KumaCheckApp.kt:95`](../app/src/main/java/app/kumacheck/KumaCheckApp.kt#L95) — registered at startup; `grep -rn "unregisterNetworkCallback"` returns
nothing. The inline comment notes the choice was deliberate ("kept for
future paths that may tear it down"). Because `Application` is a
process-lifetime singleton, the callback dies with the process — not a
real leak today, but the moment anyone adds a "switch to alternate
process" or "selectively disable monitoring" feature, this needs to
unregister cleanly.

**Fix:** when adding any teardown path, call
`cm.unregisterNetworkCallback(networkCallback!!)` first.

---

### Settings

#### ST1. Sign-out has no confirmation dialog — Medium — done

[`SettingsScreen.kt:139`](../app/src/main/java/app/kumacheck/ui/settings/SettingsScreen.kt#L139) — `SignOutButton(onClick = vm::signOut)`. One tap, no confirm, immediate
session loss. Same for the per-server delete button at
[line 88](../app/src/main/java/app/kumacheck/ui/settings/SettingsScreen.kt#L88) (`onRemove = { vm.removeServer(server.id) }`). Worse for delete: if it
was the only server, the user gets bumped back to the empty login flow.

**Fix:** wrap both in an `AlertDialog` confirm. For delete, special-case
the last-server message ("This is your only saved server. Delete?").

#### ST2. `POST_NOTIFICATIONS` denial doesn't sync the UI toggle to prefs — Medium — done

[`SettingsScreen.kt:395-420`](../app/src/main/java/app/kumacheck/ui/settings/SettingsScreen.kt#L395-L420) — the toggle visually flips back to "off" on permission denial because
its computed value uses `ui.notificationsEnabled && hasPostPerm`, but the
prefs write of `notificationsEnabled = true` already happened. Next launch
the toggle re-flips on with no permission, and the user sees the same
loop.

**Fix:** when the permission request returns `false`, also call
`vm.setNotificationsEnabled(false)` so the prefs reflect the actual
runtime state.

#### ST3. App version is hardcoded, not from `BuildConfig` — Low — done

[`SettingsViewModel.kt:164`](../app/src/main/java/app/kumacheck/ui/settings/SettingsViewModel.kt#L164) — `const val APP_VERSION = "0.5.0"`. `app/build.gradle.kts` already
exposes `versionName = "0.5.1"` — the constant is now stale by one
release. The release script doesn't bump it.

**Fix:** swap to `BuildConfig.VERSION_NAME`. Add `buildConfig = true` to
`buildFeatures` in `app/build.gradle.kts` if not already on. Drop
`APP_VERSION` from the ViewModel.

---

### UX gaps

#### UX1. No persistent offline / disconnected indicator outside Settings — Medium — done

The only place the socket connection state is surfaced is the
`ServerCard` in Settings. Users on Overview / Monitors / Detail have no
hint that the socket is in `DISCONNECTED` or `LOGIN_REQUIRED` and that
the data they're staring at is stale.

**Fix:** thin top-of-screen banner (or a colour-coded status dot in the
top app bar) that appears whenever
`socket.connection != AUTHENTICATED && != CONNECTED`. Tapping it offers
"Reconnect now."

#### UX2. Error states across status pages, monitor history, and incident detail lack Retry — Medium — done

[`StatusPagesListScreen.kt:70-79`](../app/src/main/java/app/kumacheck/ui/statuspages/StatusPagesListScreen.kt#L70-L79),
[`StatusPageDetailScreen.kt:74-79`](../app/src/main/java/app/kumacheck/ui/statuspages/StatusPageDetailScreen.kt#L74-L79),
[`MonitorDetailScreen.kt:379-390`](../app/src/main/java/app/kumacheck/ui/detail/MonitorDetailScreen.kt#L379-L390),
[`IncidentDetailScreen.kt:79-101`](../app/src/main/java/app/kumacheck/ui/incident/IncidentDetailScreen.kt#L79-L101) — every error state is text-only with no retry affordance. Pull-to-refresh
exists on Overview / Monitors but not on these screens, so the only
recovery path is "navigate away and back."

**Fix:** standardise an `ErrorRetryRow(message, onRetry)` composable and
use it in every screen that has an `error: String?` state.

#### UX3. No save-success feedback — Low — done

`MonitorEditScreen` and `MaintenanceEditScreen` navigate away on
successful save without a toast / snackbar. On a slow network where save
takes 2-3 s, users may double-tap Save thinking the first tap didn't
register (the in-flight guard prevents the duplicate, but the second tap
silently swallowed gives no feedback).

**Fix:** brief snackbar ("Saved") before the navigate-back. If the form
view is unmounted before the snackbar shows, host it on the parent
screen.

#### UX4. `imePadding()` is missing on long edit forms — Low — done

[`MonitorEditScreen.kt`](../app/src/main/java/app/kumacheck/ui/edit/MonitorEditScreen.kt) (LazyColumn at line ~111) and
[`MaintenanceEditScreen.kt`](../app/src/main/java/app/kumacheck/ui/maintenance/MaintenanceEditScreen.kt) — neither wraps the form in `Modifier.imePadding()`. On a small phone
with the keyboard up, focused fields can scroll under the IME and the
Save button in the top bar can be obscured by the status notch + IME
combo on some OEM ROMs.

**Fix:** `LazyColumn(modifier = Modifier.imePadding(), …)` (or the
parent Scaffold's `contentWindowInsets`).

#### UX5. Splash phase animation is timed (1.2 s steps), not state-bound — Low — done

[`SplashScreen.kt`](../app/src/main/java/app/kumacheck/ui/splash/SplashScreen.kt) cycles "Reading prefs… → Connecting socket… → Authenticating…" on a
fixed 1.2 s timer regardless of where the actual flow is. On a fast
device the user sees all three in 4 s; on a slow one they see
"Authenticating…" while the socket is still in `CONNECTING`. The
8-second `withTimeout` (verified in pass 3) does eventually push to
Login on hung connections, so it's not "stuck forever" — just visually
misleading.

**Fix:** drive the phase from `socket.connection` state directly:
`DISCONNECTED → "Connecting"`, `CONNECTED → "Authenticating"`,
`AUTHENTICATED → "Ready"`. Keep the 8 s timeout as a guard rail.

---

## Updated top 5 (after pass 5)

1. **A1** — `clearSession` purges per-server pins / mutes (privacy).
2. **A2** — pass `expectedUrl` through `runAutoReauthLoop` (security).
3. **O2** — persist dedupe before posting notification (notification spam).
4. **ST1** — confirmation dialogs for sign-out and server delete (data
   loss prevention).
5. **UX1** — persistent offline / disconnected indicator (data freshness).

Pass 1 → 5 collectively now flag **~50 actionable items** across
security, persistence, services, widgets, auth, Compose, ViewModels,
parsing, lifecycle, tests, observability, a11y/i18n, theme, CI, build,
KumaSocket internals, Settings, and UX. Nineteen false positives are
documented so they don't resurface.

---

## Pass 5 false positives (don't re-discover)

- **"Splash stuck forever on connection failure"** — there's an 8 s
  `withTimeout` at
  [`SplashViewModel.kt:44`](../app/src/main/java/app/kumacheck/ui/splash/SplashViewModel.kt#L44)
  that punts to `Decision.ToLogin` on `TimeoutCancellationException`.
  Phase animation is misleading (UX5), but the flow does terminate.
- **"`NetworkCallback` is a memory leak (High)"** — `KumaCheckApp` is the
  process-lifetime `Application`; the callback dies with the process.
  Filed as KCA2 (Low) with a note for whoever adds dynamic teardown
  later.
- **"`KumaSocket.call()` double-resume race"** — already in pass 2
  false positives.
- **"`migrateTokensIfNeeded` not idempotent"** — already in pass 2
  false positives.
- **"`KumaCheckApp` per-server cache stale on switch"** — duplicates
  pass-2 K1 (already addressed); the listener does call
  `socket.disconnect()` before reconnecting, which clears `_monitors`
  etc. when `clearState=true`. Verify behaviour before re-filing.

## Pass 4 false positives (don't re-discover)

- **"Status colors lack a non-color cue (color-blind users)"** — false
  positive: every status indicator pairs the colored dot/icon with a
  textual label (`"Up" / "Down"`, `"Recovered"`, `"X incidents"`).
- **"`MaintenanceDetailViewModel` swallows `CancellationException`"** —
  the catch happens inside `viewModelScope`; the scope's `Job` honours
  cancellation regardless of what the ViewModel's local `try/catch` does.
  Defensive log pattern, not a structured-concurrency violation.
- **"App icon foreground typo at line 20"** — re-read; `#D9D97757` is a
  valid `#AARRGGBB` (alpha `0xD9` over the terra colour). Not a typo.

## Pass 3 false positives (don't re-discover)

- **"`IncidentDetailViewModel.first()` will crash when no UP beat
  exists"** — guarded by `if (lastSeenMs != null)` at
  [line 162](../app/src/main/java/app/kumacheck/ui/incident/IncidentDetailViewModel.kt#L162). The duplicate scan is a code-quality nit (V3), not a crash.
- **"`MaintenanceEditViewModel` strategy switch sends stale dates to the
  server"** — `buildMaintenancePayload` is strategy-aware, so the wire
  payload is correct. Only the *display* lingers (M2).
- **"`StatusPagesListViewModel` race wipes loaded data"** —
  `viewModelScope.launch` blocks queue but don't run until after
  `init {}` finishes, so `giveUpJob` is always assigned before the
  cancel call. Fragile pattern (V5), not a real bug.
- **"`ManageViewModel.setActive` race fires double RPC on tap"** —
  Compose taps land on Main, where the read-then-write serialises.
  Pattern is brittle (V4), but no observed bug.

## Pass 2 false positives (don't re-discover)

- **"`KumaSocket.call()` can double-resume on timeout + ack race"** — the
  `pendingCalls.remove(cont)` guard at
  [`KumaSocket.kt:495`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L495)
  / [`L524`](../app/src/main/java/app/kumacheck/data/socket/KumaSocket.kt#L524) returns `false` on the second caller, so the second `cont.resume(...)`
  never fires. The pattern is correct.
- **"`migrateTokensIfNeeded()` is not idempotent"** — the entire body runs
  inside `dataStore.edit { … }`
  ([line 359](../app/src/main/java/app/kumacheck/data/auth/KumaPrefs.kt#L359)),
  which is transactional. A crash mid-edit rolls back. The migration is
  effectively idempotent.
- **"`TickingNow` `LaunchedEffect(Unit)` is wrong"** — intentional. Comment
  at the call site explains it; the effect is a forever-loop tied to
  composition lifetime.
- **"Active server switch doesn't stop the service"** — `KumaCheckApp`
  observes `activeUrl` / `notificationsEnabled × token` and re-issues the
  socket connect; the service consumes the same socket flows, so it picks
  up the new server automatically.
- **"Password held in `LoginViewModel` memory is a vulnerability"** —
  unavoidable for any app that accepts a password; the comment in the
  ViewModel already calls out that it's deliberately *not* persisted.
