# Cloud captions: which service?

Ozen can send what is said to a cloud speech service that writes the
captions. You choose the service in Settings and paste your own key: the
phone talks to the service directly, nothing passes through a server of
ours, and each service's key stays in the phone's Keychain. If the key stops
working, the credit runs out or the internet goes, a Whisper model already on
the phone takes over and the status line says so.

Ozen offers Soniox, Deepgram, OpenAI, Groq, ElevenLabs, Google Gemini,
Speechmatics, AssemblyAI and OpenRouter (which passes the audio on to a
Google Gemini model).

## Which one should I use?

- Soniox. On ivrit.ai's public Hebrew leaderboard (below) it is the most
  accurate cloud service, and it is the cheapest: about 12 cents for every
  hour the captions run. The words appear as they are said, over one
  connection that stays open.
- Deepgram, to try cloud captions for free: a new account gets $200 of free
  credit with no card, which is many months of captions. It is the second
  most accurate cloud service on the same test, ahead of OpenAI's models.
- OpenAI, Groq or ElevenLabs, for anyone who already has one of their
  keys. Groq is the cheapest of the three and has a free plan, but its
  words only appear once each sentence is finished.
- Google Gemini, for anyone with a Google account who wants to start without
  paying: Google's free plan needs no card. On that plan Google may use what
  is sent to improve its products and people at Google may read it, so for
  private conversations turn billing on first (or pick another service).
- Speechmatics, for words that appear as they are said like Soniox's, with
  $100 of free credit for a new account and no card: about 125 hours of
  captions. After that it costs several times what Soniox does.
- AssemblyAI, the same way: words as they are said, $50 of free credit with
  no card (about 88 hours of captions), then about 57 cents an hour.
- OpenRouter stays for anyone who already has a key.

For Hebrew, the phone's own Hebrew model is about as accurate as Soniox on
that test (better on voice notes, a little worse on podcasts) and more
accurate than every other cloud service tested there, so the cloud is
mainly for a phone that is too slow for its own model. Before relying on a
service, try it on a real conversation: the test used clean recordings, and
a kitchen table is harder.

To compare them on one of your own recordings before choosing,
[`scripts/cloud-trial/try_cloud_services.py`](../scripts/cloud-trial/try_cloud_services.py)
sends it to each service you have a key for, the way Ozen sends it, and
prints what each one wrote and how long it took. Given the words that were
really said, it also prints the share of words each got wrong. Its first
lines say how to run it.

## How they compare

Accuracy is the share of words wrong on ivrit.ai's
[Hebrew transcription leaderboard](https://huggingface.co/spaces/ivrit-ai/hebrew-transcription-leaderboard)
(results as of June 2026), on its podcast set and its WhatsApp voice-note set;
lower is better. ivrit.ai, which runs it, also made the phone's own model.
Each figure is the leaderboard's word-level results for that set, added
up. Groq's are for the open Whisper large-v3 model that Groq runs (Groq
itself is not on the leaderboard), and the phone's own model's are for
ivrit.ai's May 2025 Turbo, the one Ozen ships. Prices are each service's list price per hour of audio in
October 2026.

Soniox, Speechmatics and AssemblyAI get the microphone as one stream for as
long as the captions run, so they cost their list price for every hour of
captions, quiet moments included.
The other services get a request per sentence, sent only while someone
speaks, but each sentence is sent again every couple of seconds while it is
being said so the words appear as they are spoken: an hour of speech costs
about two and a half times their list price. Groq is the exception: it
counts every request as at least ten seconds, so Ozen sends it each sentence
once, when it ends, which comes to about two and a half times its list price
too, with no words until the sentence is finished.

| Service | In Ozen | Hebrew, podcasts | Hebrew, voice notes | List price per hour | Free to start |
|---|---|---|---|---|---|
| Soniox (stt-rt-v5) | yes | 4.8% | 9.0% | $0.12 | not stated |
| Deepgram (Nova-3) | yes | 6.7% | 12.1% | $0.26 | $200, no card |
| OpenRouter (Google Gemini) | yes | not tested | not tested | see openrouter.ai | no |
| OpenAI (gpt-4o-transcribe) | yes | 7.3% | 12.6% | $0.36 | no |
| Groq (Whisper large-v3) | yes | 9.8% | 13.3% | $0.11 | free tier |
| ElevenLabs (Scribe v2) | yes | not tested (Scribe v1: 20.0%) | not tested (v1: 26.4%) | $0.22, $0.27 with names | free plan |
| Google Gemini (gemini-3.5-transcribe) | yes | not tested | not tested | about $0.30 | free plan, no card |
| Speechmatics (Enhanced) | yes | not tested | not tested | $0.80 | $100, no card |
| AssemblyAI (Universal-3.6 Pro) | yes | not tested | not tested | $0.57 with speakers | $50, no card |
| The phone's own model (ivrit.ai Turbo) | built in | 5.3% | 7.1% | free | - |

Mistral's speech model has no Hebrew, and Azure and Google Cloud Speech need
a cloud project set up around the key, so they are not planned.

## How quickly the words appear

The services reach the screen in three different ways, and that matters
more than any one speed figure.

| Service | How the words arrive in Ozen | Measured delay |
|---|---|---|
| Soniox | as they are said, over one open connection | finished words 0.06 s after the speech ends (Artificial Analysis) |
| Speechmatics | as they are said, over one open connection | first guesses "typically" under half a second (Speechmatics); Ozen asks for finished words within 2 seconds |
| AssemblyAI | as they are said, over one open connection | first words 0.47 s after the speech ends (Artificial Analysis) |
| Deepgram, OpenAI, ElevenLabs, Google Gemini | in steps, about every 2 seconds while someone talks | no independent figure for the way Ozen calls them |
| OpenRouter | in steps, as above | about 1.6 s a request for the fast model and 4 s for the more accurate one (our test, September 2026) |
| Groq | all at once, when the sentence ends | no independent figure |

**As they are said** (Soniox, Speechmatics, AssemblyAI). The microphone goes
to the service over one connection that stays open, and the words show up
while the person is still talking, then settle a moment later. Ozen asks
Speechmatics to settle each word within 2 seconds, the setting Speechmatics
itself recommends for most uses (its fastest is 0.7 s, less accurate).

**In steps** (Deepgram, OpenAI, ElevenLabs, Google Gemini, OpenRouter). These
get a recording of the sentence so far, sent again after every 2 seconds of
speech, so the line grows in jumps of a few words. If a request takes
longer than 2 seconds, the next one waits for that much more speech, so a
slow connection makes the jumps further apart rather than piling up
requests. Deepgram and ElevenLabs also sell separate live services that are
faster, but Ozen does not use them, so their published live figures do not
apply here.

**At the end** (Groq). Groq charges every request as at least ten seconds,
so Ozen sends each sentence once, when it is finished: nothing shows until
then, and then the whole sentence appears together.

For the services that get a request per sentence, a line counts as finished
after 0.7 seconds of quiet, the same pause the phone's own model uses, or
after 28 seconds of speech with no pause, cut at the quietest moment of its
last 2 seconds. Speechmatics is told the same 0.7 seconds; Soniox and
AssemblyAI decide where a sentence ends by themselves.

About the figures: Artificial Analysis's
[streaming test](https://artificialanalysis.ai/articles/new-streaming-speech-to-text-benchmark-aa-wer-streaming)
(June 2026) used English recordings and measured from the end of the speech
to the first or finished words. Speechmatics' figure is its own, from its
[documentation](https://docs.speechmatics.com/features/realtime-latency).
None of the services publishes a Hebrew figure, and a phone on mobile data
adds its own delay. The trial script above prints how long each service took
on your own recording and connection.

## Languages

Ozen captions Hebrew speech: every service is asked for Hebrew, and all nine
write it. Ozen's buttons and settings come in twelve languages, but there is
no setting yet for the language people speak.

Each service's reach in those twelve is kept in the app for the day there is
one, and then a service that lacks the chosen language says so under its
name:

- Soniox and Speechmatics write eleven, all but Amharic. Chinese goes to
  Speechmatics as Mandarin.
- AssemblyAI writes ten, all but Ukrainian and Amharic.
- Deepgram writes eleven, Chinese among them. Amharic it does not have.
- OpenAI, Groq and ElevenLabs take all twelve, Amharic too; their
  accuracy outside Hebrew was not measured here.
- Google Gemini takes all twelve, each as a regional tag (European
  Portuguese for Portuguese); its accuracy was not measured here in any
  language.
- OpenRouter takes all twelve; Gemini's accuracy outside Hebrew was not
  measured here.

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

### OpenAI

1. Sign in at [platform.openai.com](https://platform.openai.com) and add
   credit under Billing.
2. Create a key on the [API keys page](https://platform.openai.com/api-keys).
3. In Ozen, pick OpenAI as the service, paste the key and save it.

### Groq

1. Sign in at [console.groq.com](https://console.groq.com).
2. Create a key on the [API keys page](https://console.groq.com/keys).
3. In Ozen, pick Groq as the service, paste the key and save it.

Groq's free plan has usage limits; past them, the phone's own model carries
on for a while.

### ElevenLabs

1. Sign in at [elevenlabs.io](https://elevenlabs.io).
2. Create a key under Developers, API keys. A key limited to some features
   needs Speech to Text allowed.
3. In Ozen, pick ElevenLabs as the service, paste the key and save it.

### Google Gemini

1. Sign in to [Google AI Studio](https://aistudio.google.com) with a Google
   account.
2. Create a key on the [API keys page](https://aistudio.google.com/apikey).
   Keys start on the free plan; turning billing on for the key's project
   moves it to the paid plan.
3. In Ozen, pick Google Gemini as the service, paste the key and save it.

### Speechmatics

1. Create an account at [portal.speechmatics.com](https://portal.speechmatics.com).
   No card is asked for.
2. In the Speechmatics portal, create an API key.
3. In Ozen, pick Speechmatics as the service, paste the key and save it.

### AssemblyAI

1. Create an account at [assemblyai.com](https://www.assemblyai.com/dashboard/signup).
   No card is asked for.
2. Copy the API key shown in the AssemblyAI dashboard.
3. In Ozen, pick AssemblyAI as the service, paste the key and save it.

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
- Deepgram: audio is sent only while someone is speaking, with as many
  names from the top of the lists as fit Deepgram's limit (about fifty
  short names).
  Every request from Ozen tells Deepgram not to use the audio to improve its
  models (`mip_opt_out`).
- OpenAI: audio is sent only while someone is speaking, with the names
  that fit Whisper's prompt (about the first twenty). OpenAI's terms apply.
- Groq: each sentence is sent once, when it ends, with the same names.
  Groq's terms apply. Off Wi-Fi this uses about 150 MB of mobile data an
  hour of speech.
- ElevenLabs: audio is sent only while someone is speaking, with the first
  hundred names as key terms (a name of more than five words is left out,
  as ElevenLabs does not take it). ElevenLabs' terms apply.
- Google Gemini: audio is sent only while someone is speaking, with the first
  hundred names as its vocabulary. On Google's free plan, Google uses what
  is sent to improve its products and human reviewers may read it; with
  billing on, it does not
  ([Gemini API terms](https://ai.google.dev/gemini-api/terms)).
- Speechmatics: the microphone streams to Speechmatics the whole time
  captions run, quiet moments included, with the first hundred names sent
  once as it connects. Speechmatics' terms apply. Off Wi-Fi this uses about
  120 MB of mobile data an hour.
- AssemblyAI: the microphone streams to AssemblyAI the whole time captions
  run, quiet moments included, with the first hundred names sent as it
  connects. AssemblyAI's terms apply. Off Wi-Fi this uses about 120 MB of
  mobile data an hour.
- OpenRouter: audio is sent only while someone is speaking, with the lists,
  and passed on to Google; OpenRouter's and Google's terms apply.

Off Wi-Fi, Deepgram, OpenAI, ElevenLabs, Google Gemini and OpenRouter use a
few hundred MB of mobile data an hour of speech, up to about 1 GB, because
each sentence is sent several times.
