# Contributing to KumaCheck

Thanks for considering a contribution. KumaCheck is a one-person Android
client for [Uptime Kuma](https://github.com/louislam/uptime-kuma); the bar
for accepting outside changes is "does it improve the experience for
self-hosted Kuma users on Android without compromising stability."

## Before opening a PR

- **Open an issue first** for anything beyond a typo or one-line bug fix.
  A short discussion before the work starts saves time on both sides if the
  scope or approach turns out to be off.
- **Check the audit** — [`docs/CODE_AUDIT.md`](docs/CODE_AUDIT.md) is the
  active punch list. Items marked **deferred** describe the rationale; if
  you want to take one of them on, please confirm in the issue thread that
  the rationale doesn't still apply.
- **Stay green** — `./gradlew :app:testDebugUnitTest :app:lintDebug` should
  pass on every commit. CI runs the same checks plus `assembleRelease`
  (R8 / Proguard regression detection).

## Code style

- Kotlin Coffee Conventions: 4-space indent, trailing commas on
  multi-line constructors, named arguments encouraged for booleans.
- Don't add comments that restate what the code already says. Comments
  should capture the *why* — a hidden constraint, a workaround for a
  known bug, surprising behaviour a future reader would otherwise have
  to discover.
- Compose: prefer `collectAsStateWithLifecycle()` over the raw
  `collectAsState()`; sweep already done in C7.
- ViewModels: use the `Flow<T>.stateInVm(this, default)` helper instead
  of the inline `combine().stateIn(...)` boilerplate.
- New screens: per-route `viewModelFactory { initializer { ... } }` lives
  in [`AppNav.kt`](app/src/main/java/app/kumacheck/ui/AppNav.kt) — see
  the existing routes as templates.

## Testing

- Pure-logic changes need a unit test in `app/src/test/`. Look at the
  existing tests for the call-site shape — `KumaJsonTest`,
  `OverviewViewModelComputeTest`, and `AutoReauthTest` are good models.
- UI changes need a manual smoke test in your PR description (which device
  / API level / what you tapped).
- Network/socket changes — please describe the Kuma server version (1.x
  vs 2.x) you tested against, since the wire formats differ in subtle
  ways.

## Commit messages

- Imperative mood, ~70 chars subject line.
- Reference the audit code if the PR closes one (e.g. "K3: bail stale
  socket handlers via generation token").
- Squash trivial fixup commits before merge.

## What we won't accept

- New transitive dependencies for one-off features (each one expands the
  attack surface for an app that holds session tokens). If you think one
  is essential, justify it in the issue thread.
- Telemetry / analytics. KumaCheck talks only to the user-configured Kuma
  server; no third-party endpoints.
- Cosmetic-only refactors without a measured improvement. Changing
  formatting / comment style across many files just adds review burden.

## Asking for help

Open a GitHub issue with a clear repro and the device/API level.
KumaCheck is maintained by one person; expect responses on a hobby-time
cadence.
