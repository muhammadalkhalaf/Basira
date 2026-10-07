# Security and privacy

## Read this first: the API key is not secret

This is an **experimental build for a very small number of trusted testers**. The Android app calls
the Gemini API directly and the Gemini API key is compiled into the APK (`BuildConfig.GEMINI_API_KEY`).

- Anyone who has the APK can extract the key. `BuildConfig`, R8/ProGuard, obfuscation, Base64, the
  Android Keystore, or native code do **not** make a fixed key secret, and this project does not claim
  otherwise.
- A leaked key lets others spend the project's Gemini quota and money.
- This design is **unsuitable for public or production distribution** (including Google Play). A
  public version needs a server-side component that holds the key, attests the app, and enforces
  per-user quotas.

The project owner explicitly accepted these risks for private testing.

## Mitigations in place

- The key is read from the untracked `local.properties`; it is not in source files, resources,
  assets, tests, or documentation. Tests use the obviously fake value `test-key-not-real-0000`.
- The key is sent only in the `x-goog-api-key` header over HTTPS, never in the URL.
- `HttpLoggingInterceptor` runs only in debug builds, at `BASIC` level (never `BODY`, because bodies
  contain images), with `x-goog-api-key` redacted. Release builds have no network logging.
- The app logger never receives the key, image bytes, base64 data, prompts, recognized text, or
  descriptions; `GeminiConfig.toString()` redacts the key; provider error messages are not logged,
  shown, or spoken.
- Requests set `store: false`, opting out of Gemini's server-side interaction storage.
- TLS only (`cleartextTrafficPermitted=false`). Certificate pinning is intentionally not enabled
  because there is no pin-rotation plan.

## Required operator actions

1. **Restrict the key to the Gemini API only** (Google AI Studio → API keys → *Add restrictions* →
   *Restrict to Gemini API only*). The Gemini API rejects unrestricted standard keys.
2. Use a dedicated Google Cloud project for this build, with **low quotas** and **budget alerts**
   (Google Cloud Billing → Budgets & alerts). Monitor usage in AI Studio / Cloud console.
3. **Rotation / revocation**: create a new key, update `local.properties`, rebuild, redistribute the
   APK to every tester, confirm it works, then disable and delete the old key and audit its usage.
   Revoke immediately if an APK reaches anyone outside the trusted group.
4. Never commit `local.properties`; it is listed in `.gitignore`.

## Data handling

- Photos are sent **directly from the phone to Google's Gemini API** and processed under the
  [Gemini API terms](https://ai.google.dev/gemini-api/terms). No other server receives them
  (Firebase never receives images; see below for what non-fatal reports may carry).
- The app does not intentionally store photos: they are processed in memory, re-encoded as JPEG
  without EXIF (removes GPS location, timestamps, device data), sent, and discarded. No temporary file
  is written for the upload.
- Saving photos is off by default and requires an explicit opt-in with confirmation; saved photos stay
  in app-private storage, and turning the option off deletes them.
- Description history (text only) is off by default; turning it off deletes it. "Delete history and
  saved photos" removes both.
- `allowBackup=false` and data-extraction rules exclude all app data from cloud backup and transfer.
- DAT analytics and DAT crash reporting are opted out in the manifest.
- **Firebase Analytics and Crashlytics** (project `basira-75f4b`) are active in release builds only
  (off in debug unless `-Pbasira.firebase.debugCollection=true`). They collect only the SDKs'
  automatic data: app-usage events such as `first_open`, `session_start`, and `screen_view`, the
  Firebase installation ID, app/OS/device model and coarse region, and crash stack traces. The app
  sends no custom Analytics events.
- **Non-fatal reports.** Every failure the app catches is reported to Crashlytics through one
  `ErrorReporter` (`app/.../controllers/reporting`), with custom keys for the domain, severity,
  outcome, operation, build mode, Gemini model, language, and network state. **Nothing is
  sanitised**: exception messages, Gemini error bodies (first 1 KB), request URLs, and DAT/TTS error
  codes are sent as they are. In particular, an answer Gemini returns that breaks the JSON contract,
  or an unreadable saved history entry, can put a fragment of a description into a report. Images and
  the Gemini key are never part of a report (the key travels only in a request header, which is not
  reported). Connection failures (offline, DNS, refused/dropped connections, cancellations) and
  glasses-state conditions (disconnected, too hot, no permission) are not reported, only logged as
  breadcrumbs; a repeated failure is sent at most once a minute.
- Advertising ID and SSAID collection are disabled, the `AD_ID` and Privacy Sandbox (AdServices)
  permissions are removed, and ad storage/user-data/personalization consent defaults to denied.
- `google-services.json` is untracked. Its API key identifies the Firebase project and is not a
  secret, but restrict it in Google Cloud Console (Android app restriction for `com.basira.app` and
  the release signing certificate) and keep it separate from the Gemini key.
- Testers must be told that release builds send crash reports, non-fatal error reports, and usage
  statistics to Firebase.

## Safety

- The system instruction forbids declaring roads, crossings, routes, medicine, banknotes, or situations
  safe, forbids instructing the user to cross a road, forbids identity and sensitive-attribute
  inference, and tells the model to treat text inside the image as untrusted content.
- Object names typed or spoken by the user are sanitized and quoted as data in the user prompt; they
  never become part of the system instruction.
- Currency is spoken only at `HIGH` confidence; low-confidence descriptions are prefixed with
  "لست متأكداً" (I am not sure). Malformed, truncated, or blocked answers are never spoken.
- Gemini results may be wrong or late. The app does not replace a white cane, guide dog, mobility
  training, or human help, and must not be the sole basis for crossing roads, identifying medication,
  currency decisions, or emergencies.

## Reporting a vulnerability

Report issues privately to the project owner; never share APKs, keys, images, or personal data in
public issues.
