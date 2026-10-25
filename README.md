# Spark — offline digital assistant ("Hey Spark")

Push-to-talk Android assistant, fully on-device, built on the same
stack as [HeyTune](../HeyTune) (`E:\repos\HeyTune`):

- [Whistle](https://cactuscompute.com/blog/whistle) — 16.9 MB on-device STT
- [Needle 3](https://cactuscompute.com/needle) — 8–29 MB on-device function calling

One C++ engine loads both `.cact` files; `needle_complete(audio)` returns
tool calls + `audio_text` in a single call, so the transcript never leaves
the device.

Design reference: the terminal voice agent in
[neeraj_sharma's Whistle+Needle post](https://dev.to/neeraj_sharma_757fbb49aa7/an-offline-voice-agent-for-my-terminal-in-52-mb-whistle-needle-40le),
[eva](https://github.com/x1watt/eva) (offline assistant incl. digital-assistant
role) and [AbodeLLM](https://github.com/brendmung/abodellm) (offline APK
distribution). Unlike eva/AbodeLLM (full LLM chat, GBs of weights), Spark is
function-calling only (~45 MB of models), like HeyTune.

## What it does

- Selectable as the system **digital assistant** (Settings → Apps → Default
  apps → Digital assistant app). A press of the assistant button (headset /
  steering-wheel / long-press-home) opens one **listen window** — no wake
  phrase needed inside it. **No always-on mic** (push-to-talk only, by design).
- The wake phrase **"Hey Spark"** is recognised and stripped when present
  (e.g. in-app mic button, habitual prefix), but never required.
- **Generic action mapping**: the user maps a keyword/phrase to an app
  already installed on the device, optionally with a search/play query:
  e.g. `play <song> → <music player>` sends `MEDIA_PLAY_FROM_SEARCH`,
  `open <app>` launches it. Mappings are stored on-device and resolved by
  the offline fallback parser and the intent dispatcher.
- Built-in tools: `open_app`, `play_media`, `pause_playback`,
  `resume_playback`, `next_track`, `previous_track`, `set_volume`
  (see `app/src/main/assets/spark_tools.json`, the single source of truth
  for the Needle tool schemas).
- Fully offline after a one-time ~45 MB model download
  (`whistle.cact` + `needle3.cact` from Hugging Face).

## Layout

```
.github/workflows/android.yml   # CI: arm64-v8a debug APK
app/                            # Minimal single-module Android app
  src/main/cpp/needle_bridge.cpp  # JNI -> libneedle.a (arm64-v8a only)
  src/main/assets/spark_tools.json  # Needle tool schemas
  src/main/java/com/spark/assistant/
    engine/   # NeedleBridge, ModelManager, VoiceTools, CommandParser,
              #   ActionMapping(+Store), ActionDispatcher, VoiceService
    assistant/# SparkAssistantService + session, AssistantSetup, ListenActivity
    ui/       # MainActivity (status, mappings editor, test phrase)
```

## Build the APK (GitHub Actions)

Push this repo to GitHub — the `android` workflow does everything:

1. JDK 21, Android SDK 37, NDK `27.0.12077973`, CMake `3.22.1`,
   `needle download android-arm64` (staged to `app/engine/android-arm64`),
   tool-schema JSON validation, model-URL reachability check,
   `:app:assembleDebug`.
2. Debug APK (`Spark-…apk`) uploaded as artifact `Spark-debug`.

**arm64-v8a only**: `abiFilters` is `arm64-v8a`, the CI downloads only the
`android-arm64` engine, and no ABI splits are produced.
The native lib still builds a stub on other ABIs so `assembleDebug` works
anywhere; `NeedleBridge.nativeAvailable=false` there.

No keystore needed for debug. For a signed release, add a
`keystore.properties` + signing config (same pattern as HeyTune's
`ot_release` block).

## How it works on device

1. First launch → grant mic → **Download voice models** once (~45 MB).
2. **Set Spark as digital assistant** → confirm in system Settings →
   Apps → Default apps if asked.
3. Settings → **Action mappings** → add e.g. keyword `tune` →
   your music player + play/search support; or `tube` →
   YouTube. Say *"Hey Spark, play Bohemian Rhapsody on tune"* or press
   the assistant button and say *"play Bohemian Rhapsody"*.
4. `needle_complete(audio)` → tool calls; `confidence ≥ 0.50` executes,
   otherwise a confirm notification, otherwise the offline regex
   fallback (which also knows your mappings). Agreement upgrade: if model
   and fallback produce the same call, it executes without nagging.
5. `ActionDispatcher` fires plain Android intents (`LAUNCHER`,
   `MEDIA_PLAY_FROM_SEARCH`, media-key events) — no network.

Caveat (same as HeyTune): classic Android Auto **projection** reserves its
button/mic for Gemini/Assistant and won't offer third-party assistants.
The assistant path works on the phone itself (Bluetooth, headset button)
and on AAOS-native head units.
