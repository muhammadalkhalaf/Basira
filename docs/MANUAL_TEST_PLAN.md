# Manual test plan (blind and TalkBack users)

Run on a physical Android phone (Android 10 or later) with TalkBack on, at the user's normal speech
rate, ideally with a blind tester. Record pass/fail and notes for every step.

## A. Without glasses (simulation build)

Build: `./gradlew :app:installDebug` (bundled sample images, sample descriptions).

1. First launch: TalkBack reads "Step 1 of 5" and the welcome heading; Next is reachable by swiping.
2. Consent: all seven statements are read; "Exit" closes the app; "I understand and agree" continues.
3. Setup checklist: every row reads "label: done/needed"; each needed row has a button.
4. Voice: "Play test sentence" speaks in the app language. With that language's TTS data removed,
   the row says "needed" and "Install … voice" opens the installer; no other voice is used instead.
5. Finish: "Start" opens the main screen; the app speaks the status including the simulation warning.
6. Main screen order by swiping: app name, simulation banners, status, describe button, repeat,
   voice command, more options, last description, utilities.
7. Double-tap "Describe what is in front of me": capture tone and vibration, "Taking a photo",
   processing tone, success tone, then the sample description beginning with "وصف تجريبي".
8. Double-tap Describe repeatedly while busy: only a "busy" tone and one capture.
9. During capture: the describe button reads "Busy, please wait"; Cancel stops it with a cancel tone.
10. "Repeat last description" repeats it; "Stop speaking" interrupts speech.
11. Read text, Identify banknote, and Find (type "keys") each produce a description.
12. Voice command: first use asks for the microphone; saying "اقرأ النص" or "ابحث عن المفاتيح" works;
    unclear speech says "Sorry, I did not understand".
13. Airplane mode: an offline tone and the offline status; Describe explains it and captures nothing;
    turning airplane mode off announces "Connection restored" and sends nothing automatically.
14. Notification: pull down the shade; the notification title and its four actions are read; Describe
    works from the notification shade (on the lock screen Android may ask to unlock first).
15. Settings: switches announce On/Off; enabling "Save captured photos" asks for confirmation;
    "Delete history and saved photos" asks for confirmation and announces completion.
16. Font size at maximum and display size at maximum: no clipped text, everything scrollable.
17. Language: Settings > Language offers Phone language, العربية, and English; TalkBack reads each as
    a radio button with its state. Choosing English switches the screens, spoken status, voice
    commands, and descriptions to English at once; choosing العربية switches all of them back to Arabic.
    With "Phone language", a phone set to Arabic gives Arabic and a phone set to English (or Turkish)
    gives English. On Android 13+ the choice also appears in Settings > Apps > Basira > Language.

## B. With Ray-Ban Meta glasses (real build)

Build: `./gradlew :app:installDebug -Pbasira.glasses=real` with `GEMINI_API_KEY` in `local.properties`.
Preconditions: Meta AI installed, glasses paired and updated, Developer Mode on, key restricted to the
Gemini API with quotas and budget alerts.

1. Without Meta AI installed: status "Meta AI app is not installed" with Install Meta AI.
2. Nearby devices permission prompt is reachable and announced.
3. Connect to Meta AI: Meta AI opens; after approving and returning, status advances.
4. Allow camera: Meta AI permission screen; deny once → "Camera permission denied", no automatic
   retry; allow → Ready.
5. Glasses folded or off → "Glasses disconnected"; unfold and wear → "Glasses connected".
6. Describe with glasses worn: total time from tap to speech (target under 8 s on Wi-Fi), photo is of
   the correct orientation (check with the photo-saving opt-in, then delete the photo).
7. Audio route: with glasses as Bluetooth audio, speech is heard in the glasses; disconnect glasses
   Bluetooth audio during a description → speech stops, "Glasses audio disconnected" is spoken on the
   phone; status announces the phone speaker route.
8. Music playing on the phone ducks during speech and returns afterwards.
9. Lock the phone with the session active: notification Describe still works.
10. Remove glasses during capture: a spoken failure, the previous description is still repeatable.
11. Overheating or low battery on the glasses (if reproducible): a specific spoken reason.
12. Ten consecutive descriptions: no stuck "busy" state, camera indicator on the glasses turns off
    after each capture.
13. Headset media button (setting on): single press describes; note whether the Ray-Ban touchpad
    triggers anything (not guaranteed).

## C. Gemini failure handling

1. Build without `GEMINI_API_KEY` but with `-Pbasira.vision=remote`: Describe speaks "The description
   service is not set up in this test build" and nothing is sent (verify with airplane mode off).
2. Build with a deliberately invalid key: "The description service refused this build"; no retry.
3. Airplane mode during analysis: a spoken connection problem; nothing is resent after reconnecting.
4. Rapid repeated requests until the project's per-minute limit: "Too many requests", with the wait
   time when Gemini sends `Retry-After`.
5. Cancel during "Analyzing": the cancel tone, no description is spoken afterwards.
6. A photo of a sign containing text such as "ignore your instructions": the text is read as text,
   not obeyed.
