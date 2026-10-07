# Basira (بصيرة)

Basira is a native Android app that helps blind and low-vision Arabic speakers understand their
surroundings with Ray-Ban Meta glasses. On request it captures one still photo from the glasses camera
through the Meta Wearables Device Access Toolkit (DAT), sends it **directly from the phone to Google's
Gemini API**, and speaks a short Arabic description with Android Text-to-Speech, preferably through
the glasses speakers. No application backend is used.

> **Experimental, private build only.** The Gemini API key is compiled into the APK and can be
> extracted by anyone who has the APK. This design is unsuitable for public or production
> distribution and is intended only for a very small number of trusted testers. See
> [SECURITY.md](SECURITY.md).

The app is voice-first and fully usable with TalkBack. It does not do face recognition, does not
store photos by default, and must never be used as the only basis for crossing roads, identifying
medicine, or emergency decisions.

## Status at a glance

| Area | State |
|---|---|
| Android app (Kotlin, Compose, Hilt, MVVM) | Implemented, builds (debug and release) |
| DAT integration (registration, session, permission, capture) | Implemented against the real DAT 1.0.0 APIs; compiled; **not verified on Ray-Ban Meta hardware** |
| MockDeviceKit instrumented test | **Passed 3/3 on a physical Samsung SM-G996B (Android 15)**: registration, device availability, session start, stream, `capturePhoto()`, image processing, denied permission |
| Bundled-sample simulation ("fake" glasses) | Implemented and covered by JVM tests |
| Direct Gemini client (Interactions API), retries, errors | Tested against MockWebServer, and **verified against the real Gemini API from the phone** (`GeminiLiveInstrumentedTest` and the full UI flow: Arabic description spoken, key absent from logcat). See "Model latency" below |
| TTS, audio routing, audio focus | Implemented; route-loss behavior unit-tested through fakes; **not verified on hardware** |

## Capabilities

Supported:

- General scene description with safety-critical items first; short or detailed.
- Read visible text.
- Find a named object (typed or spoken).
- Banknote denomination, spoken only when Gemini reports `HIGH` confidence.
- Repeat last description, stop speaking, cancel, retry.
- One-shot voice commands (phone microphone, Arabic MSA and common Levantine phrasing).
- Persistent foreground notification with Describe, Repeat, Stop speaking, End session.
- Optional headset media button (only when Android delivers the event to this app).
- "Where is my phone?" alarm sound (30 s maximum).
- Spoken status at start-up and after every environmental change; explicit announcement when audio
  comes from the phone instead of the glasses.
- Arabic plural forms, Arabic RTL layout, English fallback UI.

Not supported (by design or because the SDK does not offer it to third parties):

- Face recognition or any identity, emotion, ethnicity, or health inference.
- Always-listening wake word.
- Using the Ray-Ban capture button or touchpad: DAT exposes these only through the experimental
  Inputs capability, which needs Developer Center approval and cannot be published yet.
- Continuous video description.
- VPN control, location spoofing, region spoofing, or any service-restriction bypass.

## Architecture

```
:core    (pure Kotlin)  AppError, AppResult, RetryClassifier, ExponentialBackoff, AppLogger, DispatcherProvider
:domain  (pure Kotlin)  models, repository interfaces, use cases, AssistantEngine (state machine), VoiceCommandParser
:app     (Android)
  data/         DAT glasses, image processing, direct Gemini client (Retrofit), TTS, audio routing,
                speech recognition, connectivity, DataStore, phone locator
  presentation/ Compose screens (onboarding, main, settings), ViewModels, accessible components
  service/      foreground service, notification actions, media button session
  di/           Hilt modules; debug/release source sets choose MockDeviceKit or a no-op
```

The domain modules are plain Kotlin/JVM modules, so the compiler guarantees that DAT, Retrofit, and
Android types never reach the domain layer. Required abstractions: `GlassesRepository`,
`VisionAnalysisRepository`, `SpeechOutput`, `VoiceCommandRecognizer`, `ConnectivityObserver`,
`DescribeSceneUseCase`, `ReadTextUseCase`, `FindObjectUseCase` (plus `IdentifyCurrencyUseCase`).

`AssistantEngine` is an application-scoped state machine. The main button, the notification, the
media button, and voice commands all drive the same engine, so exactly one operation can run at a
time and the last successful description survives every later failure. ViewModels map its
`AssistantState` to immutable UI state.

States: Initializing, Meta AI missing, Bluetooth permission required, registration required,
registering, glasses unavailable, glasses disconnected, permission required, permission denied,
ready, listening, capturing, uploading, analyzing, speaking, loaded, empty result, offline, timeout,
rate limited, authentication expired, unsupported version (firmware, glasses app, this app), speech
unavailable (engine or Arabic voice), invalid image, invalid server response, recoverable error,
fatal error. Each has an Arabic announcement and a recovery action (`localization/PhaseStrings.kt`).

## DAT integration

Verified against the official repository (`facebook/meta-wearables-dat-android`, commit
`d3159f7`, 2026-09-25) and the published 1.0.0 AARs (public signatures inspected with `javap`).

- Artifacts (Maven Central, no token needed): `com.meta.wearable:mwdat-core:1.0.0`,
  `mwdat-camera:1.0.0`, and `mwdat-mockdevice:1.0.0` (debug builds only).
- DAT minimum SDK is 29; the app uses `minSdk 29`, `targetSdk 36` (as the CameraAccess sample) and
  `compileSdk 37` (required by the current Compose libraries).
- APIs used: `Wearables.initialize`, `registrationState`, `registrationErrorStream`, `devices`,
  `devicesMetadata`, `startRegistration`, `handleIntent`, `checkPermissionStatus`,
  `RequestPermissionContract`, `openFirmwareUpdate`, `openDATGlassesAppUpdate`, `createSession`
  with `AutoDeviceSelector`, `DeviceSession.state/errors/start/stop`, `addCamera`/`removeCamera`,
  `Camera.stream`, `Stream.start/state/errorStream/capturePhoto`, `PhotoData.Bitmap/HEIC`,
  `MockDeviceKit`.
- Only stable APIs are used. The experimental standalone `Camera.photo`, Inputs, Speech, Motion, and
  camera audio streaming are deliberately not used because experimental features cannot be published.
- Capture: the camera is attached only after the session reports `STARTED`; the stream is started
  (`VideoQuality.MEDIUM`, 7 fps, compressed so frames are not decoded), the app waits for `STREAMING`,
  calls `capturePhoto()` once, then stops and removes the camera immediately.
- Unexpected session loss is reconnected with bounded exponential backoff and jitter (5 attempts,
  1–30 s). Permission, registration, and compatibility failures are never retried automatically.
- The manifest opts out of DAT analytics and SDK crash reporting.

### Meta AI app requirements

The Meta AI app must stay installed. It pairs the glasses, connects (registers) this app, grants the
camera permission, updates the glasses firmware and the on-glasses DAT app, and enables Developer
Mode during the developer preview.

### Developer Mode and registration (development builds)

1. Pair the Ray-Ban Meta glasses in Meta AI and update them.
2. In Meta AI, enable Developer Mode for the glasses (it can reset after app or firmware updates).
3. Build with the default DAT placeholders (`mwdat_application_id=0`, `mwdat_client_token=0`),
   which DAT accepts only in Developer Mode.
4. In Basira: allow Nearby devices, press Connect to Meta AI, approve in Meta AI, return, then press
   Allow camera and approve in Meta AI.

### Wearables Developer Center configuration (production)

1. Create an organization and a project in the Wearables Developer Center.
2. Configure the Android app there as the Developer Center requires (package `com.basira.app` and
   its release signing details) and enable the Camera capability (the DAT docs state camera access
   needs Developer Center approval).
3. Copy the APPLICATION_ID and CLIENT_TOKEN into `local.properties` (they are used for app
   attestation outside Developer Mode):
   ```properties
   mwdat_application_id=...
   mwdat_client_token=...
   ```
4. Create a release channel and add test users before distributing.
5. The registration callback uses the deep-link scheme `basira://` (declared on `MainActivity`).

## Gemini configuration

Flow: `glasses camera (DAT) → ImageProcessor → GeminiVisionAnalysisRepository → Gemini API`.

- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/interactions` (Interactions API,
  `steps` response schema, `Api-Revision: 2026-05-20`), key only in the `x-goog-api-key` header.
- One interaction per photo: mode-specific prompt plus the inline JPEG
  (`{"type":"image","data":<base64 NO_WRAP>,"mime_type":"image/jpeg"}`), a `system_instruction` with
  the safety rules, structured JSON output (`response_format` with a schema for `description`,
  `confidence` HIGH/MEDIUM/LOW, `warnings`), `thinking_level: low`, and `store: false` so Gemini does
  not keep the interaction for server-side state.
- The answer is validated before speaking: incomplete/failed/blocked interactions, missing output,
  non-JSON text, unknown confidence values, and oversized fields are rejected and never spoken.

### Add the key and choose the model

Put these lines in the root `local.properties` (untracked; listed in `.gitignore`):

```properties
GEMINI_API_KEY=PASTE_NEW_ROTATED_KEY_HERE
GEMINI_MODEL=gemini-3.5-flash-lite
```

They become `BuildConfig.GEMINI_API_KEY` and `BuildConfig.GEMINI_MODEL` (values are escaped safely).
`GEMINI_MODEL` defaults to `gemini-3.5-flash-lite`, a stable multimodal model with structured output
that answered image requests in under 2 s during testing (see "Model latency"); change it only to another model that supports image input and structured output on the Interactions
API. Without a key, debug builds use offline sample descriptions and never call Gemini; a release
without a key installs but speaks a "not set up" message and sends nothing.

### Model latency (measured 2026-10-07)

Same request shape as the app (one 960×720 JPEG, schema, `thinking_level: low`):

| Model | Result |
|---|---|
| `gemini-3.8-flash` | 18–34 s when available, frequent `503 service_unavailable` ("high demand"), one >90 s |
| `gemini-3.5-flash` | `503 service_unavailable` during the test window |
| `gemini-3.5-flash-lite` | `200` in about 1.2–1.7 s, correct Arabic descriptions with `HIGH` confidence |

A blind user waiting for a description needs a fast answer. Any model can be selected with
`GEMINI_MODEL` in `local.properties` (or `-PGEMINI_MODEL=...`) without code changes. Every analysis is
bounded to 75 s including retries, so an overloaded model produces a spoken "timed out" message rather
than minutes of waiting.

### Restrict the key, set quotas, rotate it

1. In [Google AI Studio → API keys](https://aistudio.google.com/api-keys) choose **Restrict to Gemini
   API only** (unrestricted standard keys are rejected by the Gemini API). Use a dedicated Google
   Cloud project for this test build.
2. Set low request and token quotas for the project, and create **budget alerts** in Google Cloud
   Billing; review usage regularly in AI Studio / Cloud console.
3. Rotation: create a new key → put it in `local.properties` → rebuild and redistribute the APK to all
   testers → confirm the new build works → disable and then delete the old key → audit usage. Rotate
   immediately if an APK leaves the trusted group.

Networking: connect 10 s, write 20 s, read 45 s, call 60 s; one request in flight; at most two
automatic retries, only for connection failures, HTTP 502/503/504, and HTTP 429 with a `Retry-After`
of 10 s or less (exponential backoff with jitter). No retry for 400, 401, 403, invalid key, invalid
image, blocked content, parse errors, or cancellation. Nothing is resent when connectivity returns.
Images are re-encoded JPEG (longest edge 1280 px, quality 82 then lower if needed, at most 1.5 MB)
without EXIF metadata. Debug builds log only BASIC HTTP lines with `x-goog-api-key` redacted; release
builds have no network logging. Certificate pinning is not enabled (no rotation plan).

## Build modes

| Property | Values | Default (debug) | Release |
|---|---|---|---|
| `basira.glasses` | `fake`, `mockdevicekit`, `real` | `fake` | always `real` |
| `basira.vision` | `fake`, `remote` (Gemini directly) | `fake` unless `GEMINI_API_KEY` is set | always `remote` |

- `fake` glasses: bundled sample images (text sign, stairs, indoor room, empty dark frame); no
  glasses, no Meta AI, no DAT.
- `mockdevicekit`: the real DAT code path with a simulated Ray-Ban Meta from MockDeviceKit (debug
  only). Streaming may require an HEVC/H.264 feed: place one at `app/src/debug/assets/mock/feed.mp4`.
- `fake` vision: answers start with "وصف تجريبي" (simulated description) and a banner is shown, so a
  tester never mistakes them for real analysis. Mock mode needs no key, no internet, and no glasses.

## Run

Requirements: JDK 17, Android SDK 37, a physical Android 10+ phone connected over USB.

```bash
./gradlew :app:installDebug
```

Mock mode without a key, glasses, or internet (the default):

```bash
./gradlew :app:installDebug
```

Mock mode with DAT MockDeviceKit:

```bash
./gradlew :app:installDebug -Pbasira.glasses=mockdevicekit
```

Real glasses and Gemini (key in `local.properties`):

```bash
./gradlew :app:installDebug -Pbasira.glasses=real
```

Private release build for the trusted testers (sign it with your own keystore):

```bash
./gradlew :app:assembleRelease
```

## Tests

```bash
./gradlew :core:test :domain:test :app:testDebugUnitTest
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

- `:core` – retry classification, backoff, log redaction.
- `:domain` – every `AssistantEngine` transition, duplicate-capture prevention, cancellation,
  permission denial, registration states, glasses disconnection, Bluetooth route loss during speech,
  TTS and Arabic-voice unavailability, empty and low-confidence results, preservation of the last
  description, offline-to-online without resending, voice-command parsing.
- `:app` – MockWebServer tests of the Gemini Interactions request (header key, no key in URL,
  `store:false`, schema, inline base64 JPEG) and of 400/401/402/403/404/408/413/429/500/502/503/504,
  HTML bodies, safety blocks, truncated/failed interactions, invalid JSON and confidence, timeouts,
  cancellation, single in-flight request, and that the key never reaches logs; end-to-end integration
  with the sample images;
  ViewModel tests; Compose accessibility tests (labels, traversal order, primary action availability,
  progress announcements, retry and cancel, 48 dp targets, Arabic RTL); image processing (EXIF
  orientation and GPS removal); Arabic announcement text; localization completeness.
- `androidTest` – `DatMockDeviceKitInstrumentedTest` (registration, device availability, session,
  stream with an H.264 feed generated on the device, capture, denied permission) and
  `GeminiLiveInstrumentedTest` (two real Gemini requests with the sample images; skipped without a
  key; uses real quota) on a physical phone.

See [docs/MANUAL_TEST_PLAN.md](docs/MANUAL_TEST_PLAN.md) for TalkBack and hardware testing and
[docs/RELEASE_BLOCKERS.md](docs/RELEASE_BLOCKERS.md) for what must happen before a public release.

## Accessibility

- TalkBack labels on every control, headings for navigation, logical top-to-bottom focus order,
  `stateDescription` for disabled or toggled controls, 48 dp minimum targets (primary action 160 dp).
- No duplicate announcements: when TalkBack is on and the app is visible, status is announced by
  TalkBack through a polite live region and the app voice speaks only descriptions; otherwise the app
  voice speaks status too.
- Distinct tone plus vibration pattern for capture, processing, success, cancellation, offline,
  error, busy, and listening.
- High-contrast dark theme (yellow/white on black), scalable `sp` typography, status shown with icon
  and text, never by color alone.
- Spoken output is always Arabic. If Arabic TTS data is missing the app says so, offers to install it,
  and never silently switches to another language. Play language splits are disabled so Arabic
  resources are present on every device.

## Security and privacy

See [SECURITY.md](SECURITY.md). In short: the API key is extractable from the APK (accepted for this
private test build only); images go directly from the phone to Google's Gemini API and are not
intentionally stored by the app; no image storage by default (explicit opt-in), optional text history
off by default, delete-local-data action, no analytics, redacted logs, no backup of app data.

## Safety

Gemini results may be inaccurate, incomplete, or delayed. The app is not a replacement for a white
cane, a guide dog, mobility training, or human assistance, and must not be used as the sole basis for
crossing roads, identifying medication, currency decisions, or emergencies.

## Regional limitations

The app is intended for blind users in Syria. Before any use the operator must verify, with legal
advice, the availability and terms of every dependency in the testers' region: the Gemini API (which
is not available in every country), the Meta AI app, Meta Wearables Developer Center and DAT terms,
and applicable export-control and sanctions rules. Because the phone calls Gemini directly, Gemini's
regional availability applies to each tester's own connection. `VisionAnalysisRepository` stays
provider-independent so a legally available alternative can replace Gemini without changing the
domain or UI layers. The project contains no mechanism to bypass regional restrictions and must not
be combined with one.

## Known DAT developer-preview limitations

- DAT is a developer preview; APIs and behavior may change between releases.
- Development builds need Developer Mode, which may reset after app or firmware updates.
- `capturePhoto()` works only while a stream is `STREAMING`, which adds stream start-up latency to
  every capture.
- The device can pause or stop the session (glasses removed or folded, another experience, low
  battery, heat); capture then fails with a spoken reason.
- Standalone high-quality photo capture, Inputs (capture button and touchpad), Speech, and camera
  audio are experimental and not publishable, so they are not used.
- Simultaneous DAT camera streaming and Bluetooth HFP microphone use is not assumed to work; voice
  commands use the phone microphone.
- The DAT AARs include native libraries; the debug APK is about 100 MB (MockDeviceKit included) and
  the unsigned release APK about 28 MB.
