# Remaining blockers

## Before testers use the build

1. **Model quality check.** The default is now `gemini-3.5-flash-lite` (under 2 s on 2026-10-07;
   `gemini-3.8-flash` was slow or overloaded). Check its description quality with real scenes before
   wider testing (see README "Model latency").
2. **Hardware verification.** Nothing has run on Ray-Ban Meta glasses. Verify registration,
   permission, session reconnect, capture latency, photo orientation, camera release, audio routing to
   the glasses, and Bluetooth loss on at least two phone models.
3. **Meta AI app** is not installed on the test phone, so registration with real glasses has not
   been attempted (MockDeviceKit covers registration only in simulation).
4. **Key hygiene:** a new key restricted to the Gemini API only, a dedicated project, low quotas,
   budget alerts, and a rotation date (see SECURITY.md).
5. **Regional and legal review** for the testers' locations: Gemini API availability and terms (the
   phone calls Gemini directly), Meta AI app, Meta developer terms, export controls and sanctions. Do
   not deploy any restriction bypass.

## Before any public release (not planned for this build)

1. **Remove the embedded key.** A public app must not contain a provider key; it needs a server-side
   component with app attestation (for example Play Integrity) and per-user quotas.
2. Wearables Developer Center setup: organization, project, Camera capability approval, real
   `APPLICATION_ID`/`CLIENT_TOKEN`, release channel (Developer Mode cannot be relied on).
3. Privacy policy, data-protection review, and confirmation of the provider's data-use terms for the
   chosen tier.
4. Prompt and output evaluation with blind users on a labelled Arabic test set (obstacles, stairs,
   doors, Syrian banknotes, Arabic signage, low light).
5. Usability study with TalkBack users following `docs/MANUAL_TEST_PLAN.md`.
6. Release signing and key management.

## Nice to have

- A stable DAT API for the capture button or touchpad (today only the experimental Inputs capability).
- Better Syrian-dialect voice-command coverage in `VoiceCommandParser`.
- ABI splits to reduce the APK size caused by DAT native libraries.
