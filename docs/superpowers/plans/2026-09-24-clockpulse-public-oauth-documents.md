# ClockPulse Public OAuth Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish accurate privacy and terms documents for ClockPulse and connect their GitHub URLs to the Google OAuth consent screen.

**Architecture:** Two focused Markdown documents will live in separate directories so each has a stable public GitHub URL. The Google Auth Platform Branding configuration will point to the repository, privacy document, and terms document, after which the external OAuth app can be published.

**Tech Stack:** Markdown, GitHub, Google Auth Platform.

**Spec:** `docs/superpowers/specs/2026-09-24-clockpulse-public-oauth-documents-design.md`

## Global Constraints

- Write both public documents in Portuguese.
- State only behavior supported by the current ClockPulse source: Calendar read-only access, local device storage, no developer backend, no persisted OAuth access or refresh tokens.
- Use `prototypegrid@gmail.com` as the public contact address.
- Use public rendered GitHub URLs from the `main` branch.

## Review Focus

- Calendar scope: the policy must say read-only and must not imply event editing.
- Data storage: the policy must disclose locally mirrored events and associated alarms without claiming cloud storage.
- OAuth credentials: the policy must distinguish the absence of persisted tokens from Google account authorization.
- Disconnect: the policy must describe revocation and removal of associated local data.
- URLs: each Google Cloud field must point to an existing public page under `jpmgoes/ClockPulse`.

---

### Task 1: Publish public policy documents

**Files:**

- Create: `privacy-policy/README.md`
- Create: `terms-of-service/README.md`

**Interfaces:**

- Consumes: the data-handling statements in `docs/superpowers/specs/2026-09-24-clockpulse-public-oauth-documents-design.md`.
- Produces: public GitHub pages at `privacy-policy/README.md` and `terms-of-service/README.md` on `main`.

- [ ] **Step 1: Add the privacy policy document**

Write `privacy-policy/README.md` with the title `Política de Privacidade do ClockPulse`, an effective date, the read-only Calendar and basic-profile purpose, local-only storage, no developer backend, no persisted access or refresh token, the disconnect behavior, and `prototypegrid@gmail.com` contact information.

- [ ] **Step 2: Check the privacy policy for the review-focus claims**

Run: `rg -n 'somente leitura|dispositivo|não mantém|Desconectar|prototypegrid@gmail.com' privacy-policy/README.md`

Expected: the output includes a concrete statement for Calendar read-only access, device-only storage, no persisted token, disconnect, and contact.

- [ ] **Step 3: Add the terms document**

Write `terms-of-service/README.md` with the title `Termos de Serviço do ClockPulse`, an effective date, personal-use terms, non-editing Calendar behavior, dependency on Google services and device configuration, user responsibility for alarms and calendar data, availability changes, and contact information.

- [ ] **Step 4: Check terms scope and contact**

Run: `rg -n 'uso pessoal|não edita|Google|alarmes|prototypegrid@gmail.com' terms-of-service/README.md`

Expected: each required obligation and the contact address appears once or more.

- [ ] **Step 5: Commit the documents**

Run: `git add privacy-policy/README.md terms-of-service/README.md && git commit -m "docs: add OAuth privacy and terms pages"`

### Task 2: Publish documents and configure OAuth branding

**Files:**

- Modify: Google Auth Platform → Branding

**Interfaces:**

- Consumes: the committed documents from Task 1.
- Produces: published public URLs and an OAuth Branding configuration with a home page, privacy policy, and terms of service link.

- [ ] **Step 1: Push `main` to GitHub**

Run: `git push -u origin main`

Expected: GitHub accepts the design, plan, privacy policy, and terms commits.

- [ ] **Step 2: Verify the public document URLs**

Run: `curl --fail --location --silent --show-error https://github.com/jpmgoes/ClockPulse/blob/main/privacy-policy/README.md -o /dev/null` and `curl --fail --location --silent --show-error https://github.com/jpmgoes/ClockPulse/blob/main/terms-of-service/README.md -o /dev/null`

Expected: both commands exit successfully.

- [ ] **Step 3: Update Google Auth Branding**

Set these fields in Google Auth Platform → Branding and save:

```text
Application home page: https://github.com/jpmgoes/ClockPulse
Application privacy policy link: https://github.com/jpmgoes/ClockPulse/blob/main/privacy-policy/README.md
Application terms of service link: https://github.com/jpmgoes/ClockPulse/blob/main/terms-of-service/README.md
```

- [ ] **Step 4: Verify publication is available and publish the OAuth app**

Open Google Auth Platform → Audience and verify **Publish app** is enabled. Publish the external OAuth app and confirm its status reads **In production**. Note any follow-up verification requirement displayed by Google.

- [ ] **Step 5: Commit the plan-tracking update**

Run: `git add docs/superpowers/plans/2026-09-24-clockpulse-public-oauth-documents.md && git commit -m "docs: add public OAuth documents plan"`
