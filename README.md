# GCR Eco Tracker

Simple Android app that measures how long supported AI apps are open (Android UsageStatsManager) and
syncs daily totals to Firebase. A Google Classroom add-on shows teachers a per-student and class report.

Real vs not: the data is real foreground minutes of supported AI apps (see AiApps.kt). It cannot know
prompt counts, cannot read conversations, and cannot see AI used in a browser or unsupported app.

## Repo layout
- android/                Kotlin app (min SDK 29)
- backend/functions/      Cloud Function `api` (Classroom verification + reports)
- backend/public/addon/   Add-on pages: discovery, teacher view, student view
- backend/firestore.rules Student-only access to their own data
- .github/workflows/android-build.yml   builds a debug APK on every push

## 1. Firebase (needed for everything)
1. Create a Firebase project. Enable Authentication > Google and Firestore.
2. Upgrade to the Blaze plan (Cloud Functions require it; small classes stay in the free quota).
3. Add an Android app, package `org.gcreco.tracker`, plus your debug SHA-1.
4. Put `google-services.json` in `android/app/` (git-ignored). For CI, save its contents as a repo
   secret named `GOOGLE_SERVICES_JSON`.

## 2. Backend + add-on pages
1. In Google Cloud Console (same project): enable the Google Classroom API, and create/find a
   Web OAuth client ID. Add your Hosting URL (https://PROJECT.web.app) as an authorized JavaScript origin.
2. Paste the client ID into `backend/public/addon/config.js` and copy `backend/functions/.env.example`
   to `backend/functions/.env` with the same ID.
3. Deploy:
   ```
   cd backend/functions && npm install && cd ..
   firebase deploy
   ```
   This publishes the rules, the `api` function and the hosting pages.

## 3. Register the Classroom add-on
In Google Cloud Console > Google Workspace Marketplace SDK > App Configuration:
- Enable "Classroom add-on"
- Attachment discovery URL:  https://PROJECT.web.app/addon/discovery.html
- Allowed attachment URI prefix: https://PROJECT.web.app/
- OAuth scopes: classroom.addons.teacher, classroom.rosters.readonly, classroom.profile.emails, openid, email
- Visibility: private to your domain (or unlisted) while testing; a Workspace admin installs it.
Classroom add-ons are available to Google Workspace for Education accounts that include them.

## How teachers are authorised
Every request carries the person's own Google token. The function checks it with Google, then asks the
Classroom API whether that person teaches the course. Only then does it read Firestore (Admin SDK).
Students are matched to the roster by school email, so students must sign in to the app with that account.

## Data model
users/{uid}                    email (lowercase), displayName, updatedAt
users/{uid}/usage/{date_pkg}   date, packageName, appName, seconds, source, updatedAt

## Known limits
- Report window is the last 7 days by UTC date; phone totals use the phone's local date.
- Add-on token flow needs a real Classroom + Marketplace test; I could not run it from here.
