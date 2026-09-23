# Google Calendar OAuth multi-account design

## Purpose

ClockPulse will let a person select one exclusive Agenda source:

- **Local calendar** reads Android's Calendar Provider after `READ_CALENDAR` is granted.
- **Google Calendar via OAuth** reads Google Calendar directly, without reading or displaying local-provider events.

OAuth removes the dependency on Google Calendar publishing events into Android's Calendar Provider. It supports more than one Google account, and presents each connected account's provider and associated profile to the person.

## Product rules

1. Agenda has exactly one active source mode at a time: `LOCAL` or `OAUTH`.
2. A local source never reads, stores, or presents OAuth events. An OAuth source never queries or presents local-provider events.
3. First use presents a source selector. Selecting Local requests calendar permission; selecting OAuth starts Google authorization.
4. OAuth may hold multiple Google accounts. Every account row displays `Google Calendar`, profile name, and email address.
5. Connecting a second OAuth account always presents Google account selection.
6. Changing from OAuth to Local requires **Disconnect all Google accounts**. It revokes their Calendar authorization, clears their events and alarms, clears cached Google identity state, and returns to the source selector.
7. Disconnecting Local clears local mirrored events and alarms, releases calendar permission through the existing app-settings path, and returns to the source selector.
8. Event reminders use the earliest popup/default reminder supplied by the selected source. An absent reminder rings at event start.

## OAuth and Google Cloud setup

The debug application ID is `app.clock.pulse.debug`; production is `app.clock.pulse`. Google Cloud has a testing Android OAuth client registered for the debug ID and its debug SHA-1. It requests only these scopes:

- `https://www.googleapis.com/auth/calendar.readonly`
- `openid`
- `email`
- `profile`

The implementation uses Google Identity Services `AuthorizationClient` for Calendar authorization, requests an access token on demand, and calls the Google Calendar REST API directly. It does not use a backend, refresh tokens, a client secret, or the downloaded client-secret JSON. Access tokens are never stored in Room or preferences; Play services owns the token cache.

The app remains in Google Cloud Testing while development is underway. Every account added through the app must also be a Google Cloud test user. A release OAuth client using the production signing certificate is required before a release build can use OAuth.

## Data model

Room gains:

- `agenda_source`: a one-row state containing `LOCAL` or `OAUTH`; it is absent until a source is chosen.
- `oauth_accounts`: immutable account identifier, provider (`GOOGLE_CALENDAR`), provider display name, Google profile ID, profile display name, email, connection state, and last successful sync time.
- `agenda_events.connectionId`: nullable for Local, required for OAuth. Event keys are namespaced as `local:<calendarId>:<eventId>:<instance>` or `google:<accountId>:<calendarId>:<eventId>:<instance>`.

No OAuth credential, token, client secret, or calendar response cache is stored as a credential. Existing alarms remain linked through `agendaEventKey` and are cancelled before their event rows are removed.

## Synchronization

`AgendaSyncer` becomes a source router.

- Local sync retains the Android Calendar Provider implementation and only runs in `LOCAL` mode.
- OAuth sync obtains an access token for each connected account, fetches `calendarList`, then fetches upcoming event instances for every accessible calendar. It uses a window from the current local day through seven days ahead, preserves today's past events until the next day, and maps Google reminder overrides/defaults to minutes-before-event.
- OAuth results from all connected accounts are merged only within the OAuth namespace. Removing one account removes only that account's mirrored events and alarms.
- A 401/token failure clears the invalid cached token and retries authorization once; an account that still cannot authorize is marked **Reconnect required** without deleting its last known profile record. The UI never falls back to local events.
- The existing periodic worker syncs only the selected source. Interactive OAuth sync requests a token without showing a chooser; an account that needs consent is surfaced as reconnect-required until the user taps it.

## User interface

The Agenda screen has three states:

1. **Choose source**: Local calendar and Google Calendar via OAuth cards, with a concise explanation of what each reads.
2. **Local**: current agenda list, manual refresh, and Disconnect local calendar. No OAuth-account or Google-profile UI appears.
3. **OAuth**: provider/profile list above the agenda events, Add Google account, refresh, reconnect status, remove account, and Disconnect all accounts. No Local Calendar events or permission controls appear.

Source-selection and disconnect dialogs explicitly warn that changing sources removes the current source's mirrored alarms and events. The OAuth account list labels the provider as **Google Calendar** and shows the associated profile.

## Security and privacy

- Request read-only Calendar scope only at the moment a person selects OAuth.
- Never package, copy, read, or commit the supplied OAuth client-secret JSON.
- Never log tokens, authorization codes, event descriptions, or account identifiers.
- Keep Google authorization revocation behind an explicit disconnect action.

## Verification

Unit tests cover source exclusivity, event-key namespacing, reminder mapping, deletion/cancellation rules, and Calendar REST response mapping. Room migration tests cover the new source/account/event columns. Instrumented tests cover source selection, a mocked OAuth account list, adding/removing accounts, and the requirement that Local cannot be selected until every OAuth account has been disconnected. Manual device verification covers two Google test accounts, a local calendar event hidden in OAuth mode, and OAuth events visible even when absent from Android's Calendar Provider.

## Non-goals

- Editing Google events or calendars.
- Backend token exchange, server-side sync, or storing refresh tokens.
- Mixing local and OAuth events in one Agenda view.
- Release OAuth credential creation before a production signing certificate exists.
