# More cloud caption services

Status: approved by the owner 2026-10-10 ("Yes, go"). Applies to the iPhone
app now and to the Android app when it gets cloud captions.

## Why

Cloud captions reach exactly one service today: OpenRouter, which forwards the
audio to Google's Gemini. The owner rates OpenRouter among the worst options,
and the only independent Hebrew benchmark (ivrit.ai's leaderboard) shows
dedicated speech services well ahead of general chat models. Ozen captions
twelve languages (Hebrew, English, Arabic, Russian, Amharic, French, Spanish,
Ukrainian, German, Portuguese, Chinese, Hindi), and no one service is best in
all of them, so the person should be able to pick.

## What the person sees

- Settings > Cloud transcription gets a **Service** picker above the key field.
  The recommended service is listed first; each row says its price per hour
  and whether it has free credit to start.
- The key field, the Save and Delete buttons and every message ("key
  rejected", "out of credit", "no internet") work as now, for the chosen
  service. Each service keeps its own key, so switching back and forth never
  asks for a key again.
- If the chosen service does not cover the caption language, the screen says
  so under the picker and names services that do.
- The footer says where the audio goes, per service, including whether that
  service may train on it (Gemini's free tier does; the rest of the list does
  not by default).
- History shows which service and model wrote each conversation.

## Services

Added in this order; each lands on its own, tested and documented:

| # | Service | How it works | Why it is in |
|---|---|---|---|
| 1 | Deepgram (Nova-3) | one request per sentence, raw WAV | 2nd best Hebrew on the independent board; $200 free credit, no card |
| 2 | Soniox | live connection (WebSocket), words arrive as spoken | best Hebrew on the board, cheapest (~$0.12/h), never trains on audio |
| 3 | OpenAI (gpt-transcribe) | one request per sentence, file upload | familiar, keeps nothing by default |
| 4 | Gemini direct (Google AI Studio key) | one request per sentence, same prompt as today | the same models without the middleman; 100+ languages |
| 5 | Groq (Whisper large-v3 / turbo) | one request per sentence, OpenAI-compatible | cheapest, free tier |
| 6 | ElevenLabs (Scribe v2) | one request per sentence, file upload | wide language coverage incl. Amharic |
| 7 | Speechmatics | live connection | Hebrew live, $100 free credit |
| 8 | AssemblyAI (Universal) | submit, then wait | $50 free credit; finished lines only, no live preview |

OpenRouter stays, moved from the chat-completions workaround to its proper
transcription endpoint once that is tested against the current path.

Not added: Mistral (no Hebrew), Azure and Google Cloud Speech (need a portal
resource or a service account, not a pasted key), Gladia (card required).

## How it fits the code

- `CloudProvider` (OzenKit): one value per service with its name, sign-up
  link, price note, the caption languages it covers, its models, and three
  pure functions: build the request for a stretch of audio, read the answer
  into lines (speaker changes become new lines, as today), and say what a
  refusal means (`CloudSpeechError`). The key check is per service too.
- `CloudSpeechEngine` keeps everything it does now (cutting sentences, live
  re-sends, retries with growing pauses, the key approval window, the cut-off
  mark) and asks the chosen `CloudProvider` for requests and answers. Its
  existing tests run unchanged against OpenRouter.
- A second engine, `CloudStreamEngine`, serves the live-connection services
  (Soniox, Speechmatics), built like `HomeServerEngine`: audio goes out as it
  arrives, provisional words show as the live line, final words finish it.
  It shares the error kinds, key handling and cover-by-the-phone behaviour.
- Keys: `CloudKeyStore` gets one keychain slot per service; the existing
  OpenRouter slot keeps its name, so a saved key survives the update.
- Settings: `AppSettings.cloudProvider` (decoding leniently, defaulting to
  OpenRouter so current settings mean what they meant) and `cloudModel`
  checked against that service's models.

## Testing

- Every service: request-building tests (address, headers, body, language,
  names list) and answer-reading tests on sample answers written from the
  service's documentation, kept as files under `Tests/Fixtures/cloud/` so the
  Android tests can read the same samples later.
- Refusals: each service's documented status codes map to the same error
  kinds (key rejected, out of credit, rate limited, server trouble).
- The engine tests that exist for OpenRouter are repeated through a second
  service, so the shared loop is proven service-independent.
- No test reaches the network. A separate script outside the app,
  `scripts/try-cloud-services.py`, lets the owner compare services on real
  recordings with their own keys before the README's recommendation is final.

## Documentation

- `docs/cloud-services.md`: for each service, how to get a key step by step,
  price per hour, free credit, which of the twelve languages it covers
  (checked against the service's own list), live or finished-lines-only, and
  what it does with the audio.
- README: a short "Which cloud service?" section with the recommendation
  (Soniox for accuracy and price; Deepgram to try for free first) and a link
  to the full page, with the evidence and its date.

## Out of scope

Running two services at once, picking a service automatically per language,
and a server of ours in between. The app keeps calling the service directly
with the person's own key.
