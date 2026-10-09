# Cloud captions: which service?

Ozen can send what is said to a cloud speech service that writes the
captions. You choose the service in Settings and paste your own key: the
phone talks to the service directly, nothing passes through a server of
ours, and each service's key stays in the phone's Keychain. If the key stops
working, the credit runs out or the internet goes, a Whisper model already on
the phone takes over and the status line says so.

Today Ozen offers Soniox, Deepgram and OpenRouter (which passes the audio on
to a Google Gemini model). OpenAI, Gemini directly from Google, Groq,
ElevenLabs, Speechmatics and AssemblyAI are planned.

## Which one should I use?

- Soniox. On the only independent Hebrew test (below) it is the most
  accurate cloud service, and it is the cheapest: about 12 cents for every
  hour the captions run. The words appear as they are said, over one
  connection that stays open.
- Deepgram, to try cloud captions for free: a new account gets $200 of free
  credit with no card, which is many months of captions. It is the second
  most accurate cloud service on the same test, ahead of OpenAI's models.
- OpenRouter stays for anyone who already has a key.

For Hebrew, the phone's own Hebrew model is about as accurate as Soniox on
that test (better on voice notes, a little worse on podcasts) and more
accurate than every other cloud service, so the cloud is mainly for a phone
that is too slow for its own model. Before relying on a service, try it on a
real conversation: the test used clean recordings, and a kitchen table is
harder.

## How they compare

Accuracy is the share of words wrong on ivrit.ai's
[Hebrew transcription leaderboard](https://huggingface.co/spaces/ivrit-ai/hebrew-transcription-leaderboard)
(results as of June 2026), on its podcast set and its WhatsApp voice-note set;
lower is better. Prices are each service's list price per hour of audio in
October 2026.

Soniox gets the microphone as one stream for as long as the captions run, so
it costs its list price for every hour of captions, quiet moments included.
The other services get a request per sentence, sent only while someone
speaks, but each sentence is sent again every couple of seconds while it is
being said so the words appear as they are spoken: an hour of speech costs
about two and a half times their list price.

| Service | In Ozen | Hebrew, podcasts | Hebrew, voice notes | List price per hour | Free to start |
|---|---|---|---|---|---|
| Soniox (stt-rt-v5) | yes | 4.8% | 9.0% | $0.12 | not stated |
| Deepgram (Nova-3) | yes | 6.7% | 12.0% | $0.26 | $200, no card |
| OpenRouter (Google Gemini) | yes | not tested | not tested | see openrouter.ai | no |
| OpenAI (gpt-4o-transcribe) | planned | 7.3% | 12.6% | $0.36 | no |
| Groq (Whisper large-v3) | planned | 9.8% | 13.2% | $0.11 | free tier |
| ElevenLabs (Scribe v1) | planned | 20.0% | 26.4% | $0.22 | free plan |
| The phone's own model (ivrit.ai Turbo) | built in | 5.3% | 7.1% | free | - |

Speechmatics and AssemblyAI are not on that leaderboard yet. Mistral's speech
model has no Hebrew, and Azure and Google Cloud Speech need a cloud project set
up around the key, so they are not planned.

## Languages

Of Ozen's twelve caption languages:

- Soniox writes eleven, all but Amharic.
- Deepgram writes eleven. Chinese goes to its older Nova-2 model, the only
  one of Deepgram's with Chinese. Amharic it does not have.
- OpenRouter is sent all twelve; Gemini's accuracy outside Hebrew was not
  measured here.

When the spoken language is one the chosen service does not have, Ozen says
so under the service.

## Getting a key

### Soniox

1. Create an account at [console.soniox.com](https://console.soniox.com/signup).
2. In the Soniox console, open your project and create a key under API Keys.
3. In Ozen, open Settings, choose cloud transcription as the speech engine,
   pick Soniox as the service, paste the key and save it.

Soniox takes what the captions cost from a balance on the account. When it
is used up, Ozen says the key is out of credit and the phone's own model
carries on.

### Deepgram

1. Create an account at [console.deepgram.com](https://console.deepgram.com/signup)
   No card is asked for.
2. In the Deepgram console, create an API key.
3. In Ozen, pick Deepgram as the service, paste the key and save it.

### OpenRouter

1. Sign in at [openrouter.ai](https://openrouter.ai) and add some credit.
2. Create a key on the [keys page](https://openrouter.ai/settings/keys).
3. In Ozen, pick OpenRouter as the service, paste the key and save it.

Each service keeps its own key, so switching between them does not ask for a
key again.

## What happens to the audio

- Soniox: the microphone streams to Soniox the whole time captions run,
  quiet moments included, with the names and important words lists sent
  once as it connects so the service can spell them. Soniox's terms apply.
  Off Wi-Fi this uses about 120 MB of mobile data an hour.
- Deepgram: audio is sent only while someone is speaking, with the lists.
  Every request from Ozen tells Deepgram not to use the audio to improve its
  models (`mip_opt_out`).
- OpenRouter: audio is sent only while someone is speaking, with the lists,
  and passed on to Google; OpenRouter's and Google's terms apply.

Off Wi-Fi, Deepgram and OpenRouter use a few hundred MB of mobile data an
hour of speech, up to about 1 GB, because each sentence is sent several
times.
