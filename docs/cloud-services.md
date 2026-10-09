# Cloud captions: which service?

Ozen can send what is said to a cloud speech service that writes the
captions. You choose the service in Settings and paste your own key: the
phone talks to the service directly, nothing passes through a server of
ours, and each service's key stays in the phone's Keychain. If the key stops
working, the credit runs out or the internet goes, a Whisper model already on
the phone takes over and the status line says so.

Today Ozen offers Deepgram and OpenRouter (which passes the audio on
to a Google Gemini model). Soniox is being added next, then OpenAI, Gemini
directly from Google, Groq, ElevenLabs, Speechmatics and AssemblyAI.

## Which one should I use?

- Deepgram, for now. On the only independent Hebrew test (below) it is the
  second most accurate cloud service, ahead of OpenAI's models, and a new
  account gets $200 of free credit with no card, which is many months of
  captions.
- Soniox, once Ozen supports it. The most accurate cloud service on the
  same test and the cheapest, but it is paid for in advance.
- OpenRouter stays for anyone who already has a key.

For Hebrew, the phone's own Hebrew model is still more accurate than every
cloud service but Soniox on that test, so the cloud is mainly for a phone that
is too slow for its own model. Before relying on a service, try it on a real
conversation: the test used clean recordings, and a kitchen table is harder.

## How they compare

Accuracy is the share of words wrong on ivrit.ai's
[Hebrew transcription leaderboard](https://huggingface.co/spaces/ivrit-ai/hebrew-transcription-leaderboard)
(results as of June 2026), on its podcast set and its WhatsApp voice-note set;
lower is better. Prices are each service's list price per hour of audio in
October 2026. In Ozen an hour of speech costs about two and a half times the
list price, because each sentence is sent again every couple of seconds while
it is being said, so the words appear as they are spoken.

| Service | In Ozen | Hebrew, podcasts | Hebrew, voice notes | List price per hour | Free to start |
|---|---|---|---|---|---|
| Deepgram (Nova-3) | yes | 6.7% | 12.0% | $0.26 | $200, no card |
| OpenRouter (Google Gemini) | yes | not tested | not tested | see openrouter.ai | no |
| Soniox | next | 4.8% | 9.0% | $0.10 to $0.12 | no, prepaid |
| OpenAI (gpt-4o-transcribe) | planned | 7.3% | 12.6% | $0.36 | no |
| Groq (Whisper large-v3) | planned | 9.8% | 13.2% | $0.11 | free tier |
| ElevenLabs (Scribe v1) | planned | 20.0% | 26.4% | $0.22 | free plan |
| The phone's own model (ivrit.ai Turbo) | built in | 5.3% | 7.1% | free | - |

Speechmatics and AssemblyAI are not on that leaderboard yet. Mistral's speech
model has no Hebrew, and Azure and Google Cloud Speech need a cloud project set
up around the key, so they are not planned.

## Languages

Of Ozen's twelve caption languages:

- Deepgram writes eleven. Chinese goes to its older Nova-2 model, the only
  one of Deepgram's with Chinese. Amharic it does not have, and Ozen says so
  under the service when Amharic is the spoken language.
- OpenRouter is sent all twelve; Gemini's accuracy outside Hebrew was not
  measured here.
- Soniox lists all but Amharic.

## Getting a key

### Deepgram

1. Create an account at [console.deepgram.com](https://console.deepgram.com/signup)
   No card is asked for.
2. In the Deepgram console, create an API key.
3. In Ozen, open Settings, choose cloud transcription as the speech engine,
   pick Deepgram as the service, paste the key and save it.

### OpenRouter

1. Sign in at [openrouter.ai](https://openrouter.ai) and add some credit.
2. Create a key on the [keys page](https://openrouter.ai/settings/keys).
3. In Ozen, pick OpenRouter as the service, paste the key and save it.

Each service keeps its own key, so switching between them does not ask for a
key again.

## What happens to the audio

Only while someone is speaking is anything sent, together with the names and
important words lists so the service can spell them.

- Deepgram: every request from Ozen tells Deepgram not to use the audio to
  improve its models (`mip_opt_out`).
- OpenRouter: passes the audio to Google; OpenRouter's and Google's terms
  apply.

Off Wi-Fi, cloud captions use a few hundred MB of mobile data an hour of
speech, up to about 1 GB.
