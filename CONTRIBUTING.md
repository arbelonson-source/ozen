# Contributing to Ozen

Ozen gives live captions and sound alerts to deaf and hard-of-hearing people.
Thank you for helping. A few things keep it working for the people who rely on it.

## Before you start

- For anything bigger than a small fix, open an issue first, so the approach
  can be agreed before the work.
- Ozen is licensed under AGPL-3.0. A pull request is published under the same
  license. There is no contributor agreement to sign.

## Check your change

Run the same portable checks CI runs:

```bash
scripts/ci-local.sh
```

It needs Swift 6.1, Python 3, and Java 21 with the Android SDK and NDK. It
checks for leftover merge-conflict markers, translations, Siri phrases, the
wording docs quote from the app, real pairing codes or addresses, and pinned
libraries. Then it runs `swift test`, the home server's tests, and the Android
core and app tests, and builds the debug APK.

- **The iPhone app** only builds for iOS (see "Building" in the README). CI
  builds and tests it on every push to a branch of this repository. Pull
  requests from forks get no CI run, so a maintainer pushes the branch here
  to test it.
- **Android device tests** (`android/app/src/androidTest`) run on an emulator
  or a phone: `./gradlew :app:connectedDebugAndroidTest` in `android/`.

## How changes are written

- **Both phones together.** Most of Ozen is written twice: in Swift
  (`Sources/OzenKit`, `App/`) and in Kotlin (`android/core`, `android/app`). A
  change to how captions, alerts, History or settings behave goes to both in
  the same pull request, with tests on both. Test cases in `Tests/Fixtures` are
  read by both test suites, so the two can't drift apart.
- **Test first.** Write a test that fails without the change, then make it
  pass. Say in the pull request how you saw it fail.
- **Comments.** A file of 100 lines or fewer has no comments. In longer files,
  a comment says why, not what.
- **Words on the screen** are written in the code as `tr("Hebrew", "English")`.
  The other languages are in `Sources/OzenKit/Resources/Translations.json`, and
  `scripts/check-translations.py` checks that every text has all of them.
  Hebrew is natural and gender-neutral (plural commands, or "אפשר ל…").
- **Docs that quote the app** must match its wording exactly
  (`scripts/check-doc-quotes.py`).
- **No real values.** No real pairing codes, tailnet names or keys anywhere,
  tests included (`scripts/check-no-real-values.py`).
- **Accessibility is the product.** Every screen has to work with VoiceOver
  and TalkBack, with braille displays, and at the largest text sizes.

## AI tools

Changes written with AI tools are welcome. Say so in the pull request, name the
tool and the model, and check the change yourself: you answer for it as if you
had written it.

## Pull requests

- One topic per pull request.
- A title that says what changes for the person using the app.
- A description that says why, what changed, and how it was checked.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.
