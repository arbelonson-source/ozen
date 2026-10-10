# Where your words go

Ozen listens to conversation and shows it as captions. This page says where
the sound and the words go, what stays on the phone, how long, and how to
delete each thing from inside the app. It says only what the app is built to
do. Where a service outside Ozen is involved, [Cloud services](cloud-services.md#what-happens-to-the-audio)
has the details for each one.

## The short version

- With the phone's own model, the sound and the words stay on the phone.
- Ozen has no account, no analytics and no tracking. It sends nothing to
  the people who made it.
- Sound leaves the phone only if you choose cloud captions or a home
  computer, or turn on Apple's servers for Apple's engine. All three are
  off until you set them up.
- Diagnostics reports are sent only when you choose to send one.

## With the phone's own model

Nothing you say leaves the phone. The sound is turned into words on the
phone itself, and Ozen has no analytics or usage-reporting code.

Ozen does go online for a few things that are not your words:

- To download a speech model, once. The files come from Argmax's model
  site or from Ozen's own page on GitHub. Like any download, the site can
  see the phone's internet address.
- To fetch the model's word list the first time a model loads. After that it
  is kept on the phone.
- To ask a cloud service whether your key still works, if you use cloud
  captions (see below).

Apple's engine is an exception you control. By default Ozen insists on
recognition on the phone. If the phone has no Hebrew on it, the switch
**Allow processing on Apple's servers** (le'afsher ibud be-shartei apel) in
Settings lets Apple's servers do it instead. It is off unless you turn it
on, and when it is on the sound goes to Apple.

## What Ozen keeps on the phone

| What | Where it lives | How long |
| --- | --- | --- |
| Saved conversations | Ozen's own storage on the phone, one small file each | Until you delete them, or the automatic deletion does |
| Settings, saved speakers' voice prints, names and alert words | One settings file in Ozen's storage | Until you change or delete them |
| Cloud keys and the home computer's pairing code | The phone's Keychain | Until you delete them |
| Sound from a marked problem | A few short clips in Ozen's storage | Up to 5, the newest kept |
| The journal | One small log file | About 500 lines; older lines drop off |
| Speech models | Ozen's storage | Until you delete the model |

### Saved conversations

Each conversation is a small file with the words, who said each line (the
name shown on screen), the times, which model or service wrote it, which
microphone was used, any stars and the title you gave it. The files are
locked by the phone after a restart until the first unlock.

**Save conversations** (lishmor shichot) in **History** (historia) turns saving
on or off. It is on at first. With it off, new conversations are not saved;
those saved earlier stay until you delete them.

**Automatic deletion** (mechika otomatit), in the same place, can delete
conversations after a week, a month, 3 months or a year. At first it is set
to Never. Conversations with a starred line or a name you gave are always
kept. A conversation you have open is left alone.

Saved conversations are in the phone's iCloud or computer backup when that is
on. The History screen says so under the switch.

### Recent sound

While captions run, Ozen holds the last 30 seconds of sound in the phone's
memory, only so that a problem can be looked into. It is never written down
unless you mark a problem, with **Mark a problem now** (lesamen be'aya
achshav) in **Diagnostics** (avchun) or from a caption line's menu, and then
only if **Save conversations** is on.
Then it makes one short clip on the phone. Up to 5 are kept, and the oldest
go first. The clips are not in iCloud or computer backups, and they are
shared only if you tap one and share it.

### Voice prints

When you add a speaker, Ozen keeps a short list of numbers that describes
the voice, with the name you typed. The recording itself is not saved, and
the screen says so. The numbers are in the settings file, which goes into
backups like the conversations.

### The journal

The journal is a short list of what happened to the captions (started,
stopped, the microphone changed, an error). It stays out of backups, and
nothing sends it anywhere by itself. When you mark a problem it also keeps
the last 4 caption lines, so someone can see the words that were wrong.

### Cloud keys and the pairing code

Each cloud service's key, and the pairing code for the home computer, are in
the phone's Keychain. They are marked as staying on this phone, so they do
not move to another phone with a backup. They are not in the report below.
The settings file holds the home computer's address, but not the code.

## How to delete each thing

| To delete | Where |
| --- | --- |
| One conversation | **History** (historia): swipe it, then **Delete** (mechika) |
| Every conversation | **History**, then **Delete all** (mechikat ha-kol) at the top. This also deletes the marked-problem clips and the caption lines in the journal |
| Old conversations, automatically | **History**, then **Automatic deletion** (mechika otomatit). Choosing a shorter time asks first when it would delete something now |
| One saved speaker's voice prints | **Settings** (hagdarot), under **Saved speakers** (dovrim shmurim): swipe the name, then **Delete** |
| One marked-problem clip | **Settings**, **Diagnostics** (avchun): swipe the clip |
| Caption lines in the journal | **Diagnostics**: **Delete the caption lines kept here** (mechikat shurot ha-ktuviyot she-nishmeru kan). It shows only when there are some |
| A cloud key | **Settings**, **Cloud transcription** (ha-timlul ba-anan): **Delete key** (mechikat ha-mafteach) |
| The home computer's pairing code | **Settings**, **Home computer** (ha-machshev ba-bayit): **Delete code** (mechikat ha-kod) |
| A speech model | **Settings**, the model screen |

The rest of the journal, which holds no words you said, has no delete button.
Deleting the app from the iPhone removes it with the rest of Ozen's files.
iOS can leave Keychain items behind when an app is deleted, so delete the
keys and the code first if you are getting rid of the app.

## With a cloud service

You choose the service and paste your own key. The phone talks to the service
directly. Nothing goes through a server of ours.

- What is sent is the sound, together with the names and important words you
  have listed, so the service can spell them.
- When it is sent depends on the service. Soniox, Speechmatics and AssemblyAI
  get the microphone the whole time captions run, quiet moments included. The
  others get sound only while someone is speaking.
- Before captions start, Ozen sends the service a small request with your key,
  to find out whether it still works. No sound is in it.
- What the service then does with the sound is up to that service's own
  terms. On Google Gemini's free plan, people at Google may read it.

Which service does what is in [Cloud services](cloud-services.md#what-happens-to-the-audio).
If the key stops working or the internet goes, a model already on the phone
takes over and the status line says so.

## With the home computer

You set up a computer of your own that writes the captions faster.

- While captions are on, the phone sends the microphone sound to the
  computer and gets the words back. When connecting it also sends the pairing
  code, your list of names, and the app and iOS version.
- The phone does not search for the computer. You scan the QR code the
  computer shows, or type its address and its pairing code in
  **Settings**, **Home computer** (ha-machshev ba-bayit). You can test it
  with **Test connection** (bdikat chibur).
- On the same Wi-Fi the address is plain (ws://), and the sound is not
  encrypted on the way. An address that starts with wss:// is encrypted.
  Ozen accepts a plain address only for a computer on the home network or
  the family's Tailscale network, never for one out on the internet, so a
  typing slip can't send the code and the sound to a stranger unencrypted.
  For use away from home, the computer's setup guide (server/README.md)
  describes a wss:// address through Tailscale.
- Only a phone that has the pairing code gets captions.
- The computer does not save the sound or the captions. Its log records the
  phone's network address, which kind of connection it was, how many minutes
  of sound came and how many lines it wrote, but not the words.
- The one thing it keeps is a diagnostics report, and only if you send one to
  it (below).

## The diagnostics report

**Settings** (hagdarot), **Diagnostics** (avchun) shows what Ozen knows about
its own state, and ends with the report. Nothing sends it by itself. It goes
out only if you tap one of these:

- **Send report** (shlichat ha-doch) opens the phone's share sheet. You pick
  where it goes, such as a messaging app.
- **Copy report** (ha'atakat ha-doch) puts the text on the clipboard.
- **Send the report to the home computer** (shlichat ha-doch la-machshev
  ba-bayit) shows only when a home computer is set up. The computer saves it
  in its reports folder and keeps the newest 50.

It contains: the app, iOS and device type, the engine and model chosen, the
language, how many sound chunks and seconds arrived, the sound levels,
how many speakers were told apart and how many are saved, which
alerts are on and how many words, the alert sounds it heard but not surely
enough to alert (each with how sure it was and when), how many names are on
your names list, whether conversations are saved and for how long, the
display size, the battery, free space, memory, heat, the type of connection,
the names of the microphones, the home computer's address, whether a code is
saved, when the installed copy stops working, and the last error, if any,
from saving settings or conversations, the lock screen captions or
notifications. After that comes the list of recent events and the journal.

It leaves out: the sound, the conversations, speaker names and voice prints,
your names list, your alert words, the cloud keys, and the pairing code.

The one place where words from a conversation can appear is the journal,
and only after a problem was marked. The sound clips are not in
the report. Each one is shared only if you tap it.

Before sending, read the report in the share sheet. A microphone can have a
name you gave it, the home computer's address can include the computer's
name, and the sounds heard below the alert level say what the phone heard
and at what time.
