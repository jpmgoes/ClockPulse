# Google Calendar OAuth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add exclusive Local and direct Google Calendar OAuth Agenda sources with multiple OAuth accounts.

**Architecture:** Room persists source choice and profile metadata. `AgendaSyncer` routes either to Android Calendar Provider or to Google Calendar REST, authorized through Google Play services; credentials remain in Play services.

**Tech Stack:** Kotlin, Compose, Room, WorkManager, Google Play services Auth, `HttpURLConnection`, kotlinx.serialization.

**Spec:** `docs/superpowers/specs/2026-09-23-google-calendar-oauth-design.md`

## Global Constraints

- Installed IDs: `app.clock.pulse` and `app.clock.pulse.debug`.
- Request only `calendar.readonly`, `openid`, `email`, and `profile`.
- Never read, package, commit, persist, or log client-secret JSON/tokens.
- Local and OAuth events are mutually exclusive.
- OAuth-to-Local requires Disconnect all accounts.

## Review Focus

- Same remote event ID in two accounts must yield two distinct event keys.
- OAuth token failure must mark reconnect-required, never fall back to local data.
- Removing account A must retain account B events and alarms.
- Default, override, and absent reminders must produce correct alarm time.
- Local controls/events must never render in OAuth mode.

---

### Task 1: Persist source, accounts, and event ownership

**Files:** Create `domain/model/AgendaSource.kt`, `domain/model/OAuthAccount.kt`, `data/database/dao/OAuthAccountsDao.kt`, `domain/repository/OAuthAccountsRepository.kt`; modify `domain/model/AgendaEvent.kt`, `data/database/AppDatabase.kt`, `AppContainer.kt`; test `AppDatabaseMigrationTest.kt`.

**Interfaces:** `enum class AgendaSource { LOCAL, OAUTH }`; `OAuthAccount(id, provider, profileId, displayName, email, state, lastSyncedAt)`; `AgendaEvent.connectionId: String?`.

- [ ] Write a failing migration test opening v16 data with a local event, migrating to v17, and asserting null `connectionId` plus two OAuth-account rows.
- [ ] Run `./gradlew testDebugUnitTest --tests '*AppDatabaseMigrationTest*'`; expect FAIL.
- [ ] Add Room entities/DAO/repository; add `connectionId`; increment database to 17; implement `MIGRATION_16_17` creating `oauth_accounts` and adding nullable `connectionId`.
- [ ] Run the same test; expect PASS.
- [ ] Commit: `git add app/src/main app/src/test app/schemas && git commit -m "feat: persist agenda OAuth sources"`.

### Task 2: Enforce exclusive source routing

**Files:** Create `domain/repository/AgendaSourceRepository.kt`; modify `util/AgendaSyncer.kt`, `util/AgendaSyncWorker.kt`; test `AgendaSourceRouterTest.kt`.

**Interfaces:** `current(): AgendaSource?`, `select(source)`, `disconnectLocal()`, `disconnectAllOAuth()`, `sync()`.

- [ ] Write failing tests that `LOCAL` invokes only local sync, `OAUTH` invokes only OAuth sync, source change is rejected while OAuth accounts exist, and Disconnect all cancels every owned alarm before deleting events.
- [ ] Run `./gradlew testDebugUnitTest --tests '*AgendaSourceRouterTest*'`; expect FAIL.
- [ ] Implement persisted source choice and source-scoped stale-event cleanup using the existing alarm-cancellation behavior.
- [ ] Run tests; expect PASS. Commit with `feat: enforce exclusive agenda sources`.

### Task 3: Authorize and map Google Calendar data

**Files:** Modify `gradle/libs.versions.toml`, `app/build.gradle.kts`; create `util/google/GoogleCalendarAuthorizer.kt`, `GoogleCalendarApi.kt`, `GoogleCalendarDtos.kt`; test `util/google/GoogleCalendarApiTest.kt`.

**Interfaces:** `authorize(activity, onProfile)`, `events(accessToken, timeMin, timeMax): List<RemoteAgendaEvent>`.

- [ ] Write failing JSON tests for two accounts with same event ID, popup override, calendar default reminder, and no reminder.
- [ ] Run `./gradlew testDebugUnitTest --tests '*GoogleCalendarApiTest*'`; expect FAIL.
- [ ] Add `com.google.android.gms:play-services-auth:22.0.0`; authorize with the four approved scopes; use in-memory access tokens only.
- [ ] Fetch Calendar List then Events List with `HttpURLConnection`; deserialize via kotlinx.serialization; produce `google:<accountId>:<calendarId>:<eventId>:<instance>` keys.
- [ ] On 401 clear cached token and retry once; persist `RECONNECT_REQUIRED` only after failure.
- [ ] Run tests; expect PASS. Commit with `feat: fetch Google Calendar events via OAuth`.

### Task 4: Sync OAuth events and alarms

**Files:** Modify `util/AgendaSyncer.kt`, `presentation/screens/agenda/model/AgendaModel.kt`; test `AgendaOAuthSyncTest.kt`.

- [ ] Write failing tests for source-exclusive results, collision isolation, account removal, today's past events, and earliest reminder selection.
- [ ] Run `./gradlew testDebugUnitTest --tests '*AgendaOAuthSyncTest*'`; expect FAIL.
- [ ] Implement OAuth sync for every accessible calendar of every connected account, from local midnight through seven days; upsert alarms and clean only the current source/account namespace.
- [ ] Run tests; expect PASS. Commit with `feat: sync OAuth calendar alarms`.

### Task 5: Source-selection and account UI

**Files:** Modify `presentation/screens/agenda/AgendaScreen.kt`, `presentation/screens/agenda/model/AgendaModel.kt`, `res/values/strings.xml`; test `androidTest/.../AgendaSourceFlowTest.kt`.

- [ ] Write failing UI tests: initial selector; Local contains no OAuth rows; OAuth contains no local controls; Local is unavailable until every OAuth account disconnects.
- [ ] Run `./gradlew connectedDebugAndroidTest --tests '*AgendaSourceFlowTest*'`; expect FAIL.
- [ ] Implement Choose Source, Local, and OAuth states; OAuth account cards show `Google Calendar`, profile name/email, reconnect/remove controls, Add account, and Disconnect all confirmation.
- [ ] Register authorization activity-result handling; pass profile metadata only to the model.
- [ ] Run `./gradlew testDebugUnitTest assembleDebug`; expect PASS. Commit with `feat: manage OAuth agenda accounts`.

### Task 6: Device verification and setup documentation

**Files:** Modify `README.md`.

- [ ] Document Google Cloud testing accounts, debug/release client requirements, and client-secret exclusion.
- [ ] On device add two test accounts; verify provider/profile labels; verify Android-provider event is hidden in OAuth; remove one account; Disconnect all; select Local and verify OAuth events are hidden.
- [ ] Run `./gradlew testDebugUnitTest assembleDebug`; expect PASS.
- [ ] Commit with `docs: describe OAuth Calendar setup`.
