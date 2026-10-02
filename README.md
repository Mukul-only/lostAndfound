# Campus Lost & Found Native Android App (Java + Views + Supabase)

A secure, native Android application built in **Java** with traditional **XML Views**, **Material Components**, and **ViewBinding** to help college students report lost or found items, verify ownership privately, chat 1-on-1, and safely coordinate returns on campus.

Backed by **Supabase** (Auth, PostgreSQL with strict Row Level Security, Storage, and RPC functions) without using Firebase or Kotlin.

---

## 🏛 Architecture Overview

- **Language & Framework**: Pure Java 11, Android Gradle Plugin 9.4+, Gradle 9.6.
- **UI System**: Traditional Android XML layouts, Activities, Fragments, RecyclerView, Material 3 Components, and ViewBinding. No Jetpack Compose.
- **Networking**: Retrofit 2 + OkHttp 3/4 + Gson.
- **Security & Privacy**:
  - `EncryptedSharedPreferences` for local JWT tokens and student sessions.
  - Server-side authorization enforced exclusively by PostgreSQL **Row Level Security (RLS)**.
  - No secret `service_role` keys stored in the Android client.
  - Strict database trigger protection against direct client tampering of report ownership, accepted claims, or status.
  - Private verification details stored in a dedicated `report_private_details` table accessible **only** to the report owner.
  - Student emails kept private in `auth.users` and omitted from the public `profiles` table.
- **Realtime / Refresh Strategy**:
  - Foreground-only message refresh (MVP fallback) running a lightweight 3.5s refresh loop between `onResume()` and `onPause()` in `ChatActivity`, along with swipe-to-refresh.

---

## 🗄 Backend Schema & Migrations

All SQL scripts are stored under `supabase/migrations/` and verified with automated test suites:

1. `001_initial_schema.sql`: Core tables (`profiles`, `reports`, `report_private_details`, `claims`, `conversations`, `messages`, `abuse_reports`), unique indexes (no duplicate active claims, single accepted claim invariant).
2. `002_functions_and_triggers.sql`: Hardened `SECURITY DEFINER` functions with safe `search_path`, trigger protection against tampering, and atomic RPC functions:
   - `create_report_with_private_details`: Atomically creates the public report and private finder note.
   - `submit_claim`: Enforces OPEN status, disallows self-claims, and rejects duplicates.
   - `accept_claim`: Atomically locks report, accepts the claim, declines competing pending claims, updates report status to `HANDOVER_ARRANGED`, and creates the handover conversation.
   - `reject_claim`: Declines a claim without closing the report.
   - `mark_report_returned`: Transitions report to `RETURNED`.
   - `close_report`: Transitions report to `CLOSED`.
3. `003_rls_policies.sql`: Complete Row Level Security policies with `FORCE ROW LEVEL SECURITY`.
4. `004_storage_policies.sql`: Storage bucket `report-photos` configuration and upload folder policies (`<user_id>/<file>`).
5. `005_rejected_claim_flow.sql`: Private rejected-claim workflow, optional rejection note, and database-level single claim constraint (`uq_claims_report_claimant`).
6. `006_map_features.sql`:
   - Adds nullable `latitude` and `longitude` with coordinate check constraints to `public.reports`.
   - Updates `create_report_with_private_details` to accept optional coordinates.
   - Creates `public.conversation_meeting_locations` table for private handover proposals and confirmations.
   - Enforces strict RLS: meeting proposals, coordinates, and notes are accessible **only** to the two accepted handover participants.
   - Atomic RPC functions: `propose_meeting_location` and `confirm_meeting_location`.
7. Test suites:
   - `supabase/tests/security_tests.sql`: Executable 19-step isolation and integrity test suite.
   - `supabase/tests/map_features_tests.sql`: Executable 10-step test suite verifying report coordinates, RLS meeting privacy, proposer self-confirmation guards, and confirmation state transitions.

---

## 🚀 Setup Instructions

### 1. Create a Supabase Project
1. Log in to [supabase.com](https://supabase.com) and create a free project (e.g. `LostAndFound-Campus`).
2. Note your **Project URL** and **Publishable Key** (`sb_publishable_...`) under *Project Settings -> API*.

### 2. Apply Database Migrations
1. In the Supabase Dashboard, open the **SQL Editor**.
2. Run the contents of the following files in order:
   - `supabase/migrations/001_initial_schema.sql`
   - `supabase/migrations/002_functions_and_triggers.sql`
   - `supabase/migrations/003_rls_policies.sql`
   - `supabase/migrations/004_storage_policies.sql`
   - `supabase/migrations/005_rejected_claim_flow.sql`
   - `supabase/migrations/006_map_features.sql`
3. Optional verification: Run `supabase/tests/security_tests.sql` and `supabase/tests/map_features_tests.sql` to execute the full automated test suite.

### 3. Create Storage Bucket
1. Go to **Storage -> New Bucket**.
2. Name: `report-photos`.
3. Set **Public bucket** to `true` (so uploaded item photos can be rendered by Glide).

### 4. Google Maps SDK Setup
1. Go to [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Maps SDK for Android** API (no paid APIs like Places, Geocoding, or Directions are required).
3. Create an API key under **APIs & Services -> Credentials**.
4. (Recommended) Restrict the key to Android applications:
   - Package name: `com.example.lostandfound`
   - SHA-1 certificate fingerprint:
     - Debug keystore SHA-1 can be obtained by running:
       ```bash
       keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
       ```
5. Add your API key to `local.properties` (this file is ignored by git and will not be committed):
   ```properties
   MAPS_API_KEY=AIzaSyYourActualGoogleMapsApiKeyHere
   ```
   *(Alternatively, pass `MAPS_API_KEY` via Gradle project properties or manifest placeholders).*

### 5. Connect Android App
Open [`SupabaseConfig.java`](file:///home/mukul/AndroidStudioProjects/LostAndFound/app/src/main/java/com/example/lostandfound/data/remote/SupabaseConfig.java) and verify your project credentials:
```java
public static final String SUPABASE_URL = "https://YOUR_PROJECT_ID.supabase.co/";
public static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_YOUR_KEY";
```

---

## 📱 Build & Run Instructions

```bash
# Build Debug APK
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug

# Run Unit Tests
JAVA_HOME=/opt/android-studio/jbr ./gradlew test
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🗺️ Google Maps Features Walkthrough

### Feature 1: Where the Item Was Found (Map Pin)
- When reporting a **FOUND** item in `CreateEditReportActivity`, the finder can tap **📍 Pin on Map**.
- Opens `LocationPickerActivity` in pin placement mode. The finder can pan, zoom, and tap or drag the pin to mark the exact campus location where the item was found.
- A prominent campus safety warning alerts students to select public campus spaces and not pin private dorm rooms or residences.
- An optional "My Location" button is available; runtime location permission is requested only if the button is clicked, while manual pin placement is always fully functional without permissions.
- On `ReportDetailActivity`, reports with saved coordinates render an interactive map preview showing the pin. Older reports without coordinates remain completely backward-compatible (the map card is hidden).

### Feature 2: Handover Meeting Location in Accepted-Claim Chat
- Once a claim is accepted, participants open `ChatActivity`.
- Either student can tap **Suggest Spot** to open the map picker and choose a well-lit, public meeting location (e.g. library entrance, student union) along with an optional short note.
- The proposal appears as a prominent meeting card in the chat.
- The other participant can review the location on the map, tap **Confirm Spot** to accept it, or propose an alternative spot.
- Proposals and confirmations are private to the two participants in that conversation and strictly protected by Supabase Row Level Security. No meeting data is ever exposed in public report listings.

---

## ⚠️ Privacy & Safety Principles
- **No Live Tracking**: The map pin represents the static spot where an item was found or an arranged handover point—never live student location tracking.
- **Campus Safety Notice**: Students are explicitly reminded in the UI to arrange handovers in public, monitored areas (such as the campus library front desk or campus police station) and never in private living quarters.
- **Participant-Only Meeting Data**: Meeting coordinates, notes, and confirmation status are stored in `conversation_meeting_locations` guarded by RLS policies verifying `auth.uid() IN (conversation.owner_id, conversation.claimant_id)`.

