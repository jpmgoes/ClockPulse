# ClockPulse public OAuth documents

## Purpose

Provide public, accurate Portuguese-language privacy and terms documents for
ClockPulse's Google OAuth consent screen. The documents let prospective users
understand the app's Calendar access before authorizing it.

## Public document layout

The repository root will contain two independently addressable documents:

- `privacy-policy/README.md`
- `terms-of-service/README.md`

GitHub's rendered `blob/main/.../README.md` URLs will be supplied in the
Google Auth Platform Branding configuration. The repository root is the
application home page.

## Privacy policy content

The policy will state that ClockPulse requests the Google Calendar read-only
scope only after a user chooses Google Calendar. It uses basic Google profile
information to identify the connected account and mirrors calendar events and
related alarms locally on the user's device. It does not operate a developer
backend, sell data, or persist OAuth access or refresh tokens. Users can
disconnect their Google account in the app; this revokes access and removes
the associated local data. Contact is `prototypegrid@gmail.com`.

## Terms content

The terms will explain that the app is supplied for personal use, does not edit
Google Calendar data, depends on Google services and device configuration, and
may change or become unavailable. The user remains responsible for alarms,
calendar data, and use of the app.

## Publication and verification

The documents will be committed and pushed to `jpmgoes/ClockPulse` on `main`.
After verifying the public URLs respond, the Google Auth Branding page will be
updated with the new home, privacy, and terms URLs. The Google Calendar
read-only scope may still require Google verification after the OAuth app is
published.

## Validation

Verify markdown paths and links locally, push the commit, check that the
GitHub URLs are publicly reachable, then confirm the OAuth console accepts the
three links.
