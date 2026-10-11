# Choosing the spoken language

Started 11 October 2026. Ozen has captioned Hebrew speech only. This plan
lets the person choose the language people around them speak, on first
launch and later in Settings, on the iPhone and on Android.

## Decisions

- **Which languages:** the twelve the interface already comes in: Hebrew,
  English, Arabic, Russian, French, Spanish, German, Portuguese, Ukrainian,
  Hindi, Chinese and Amharic. Hebrew stays the default, also for anyone
  who skips the first-launch walkthrough.
- **Both apps together:** the shared rules are written once for each app's
  shared code (`Sources/OzenKit` and `android/core`), then each app gets
  its screens.
- **Models:** Ozen's own model is trained for Hebrew only. Every other
  language uses the general Whisper model from its public page on Hugging
  Face, downloaded once. Ozen hosts no copy.

## What is already there

`AppSettings.languageCode` (default `"he"`) is saved on both apps and
reaches every engine: WhisperKit and whisper.cpp take it as the decoding
language, the cloud services check whether they cover it, and the home
computer receives it when captions start. Nothing on screen sets it yet.

## Steps

1. **The list of languages** (shared code, both apps): each language's
   Whisper code, the locale Apple's speech recognition needs (`en-US`, not
   `en`), whether it reads right to left, and its own name ("English",
   "العربية", "Русский" and so on), as the interface language
   menu already names them.
2. **Which model to recommend**: Hebrew keeps Ozen's Hebrew model; any
   other language recommends the general Turbo model (626 MB on the
   iPhone, the q5_0 whisper.cpp file of similar size on Android). The
   "Hebrew" quality dots and the weaker-model warning apply to Hebrew only.
3. **Rules that only make sense for Hebrew**, kept for Hebrew and left out
   for other languages:
   - the phrases Whisper invents in silence, which differ by language (a
     real English "you" must not be dropped);
   - spoken Hebrew number words for number highlighting (digits stay
     highlighted in every language);
   - the suggested names for alerts ("Grandma", "Mom"), offered in the
     spoken language;
   - the direction of caption lines, which follows the spoken language
     instead of always running right to left on the iPhone.
4. **iPhone screens**: a "Spoken language" row in Settings (changing it
   restarts the captions and offers the matching model), and a page in the
   first-launch walkthrough before the engine page, whose wording then
   follows the choice.
5. **Android screens**: the same row and page, and a download screen for
   the general model, which Android doesn't have yet.
6. **The home computer**: it already receives the language; it needs to
   load the general model when the language isn't Hebrew.
7. **Docs**: the README says Ozen captions Hebrew speech; it changes once
   the choice works on both apps.

## Known limits

- Amharic captions will be weak: the general models handle it poorly.
- Name alerts in Arabic and Russian match whole words only; Hebrew also
  matches a name with a prefix letter attached.
- Tappable phone numbers stay Israeli formats.
- Conversation statistics count words by spaces, which undercounts
  Chinese.

## How it is tested

Shared rules get unit tests on both sides (Swift Testing for OzenKit,
JUnit for the Kotlin core). Android screens get device tests on the
emulator; iPhone screens compile and run their tests on CI.
