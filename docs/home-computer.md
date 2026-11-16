# Captions from a computer at home

Ozen can use a computer at home to write the captions. The phone sends the
sound there and gets the words back. It is optional: the phone works fine
on its own.

## What the home computer does

Normally the phone writes the captions itself, with a speech model on the
phone. A home computer with a strong graphics card runs a bigger, faster
version of the same kind of model, so the phone does less work and stays
cooler. The sound goes to your own computer, and only while captions run.
There is no company server in between. The pairing code that lets the phone
in is kept on the computer and on the phone, nowhere else.

Compared with the other choices:

- **The phone's own model.** Always available, and nothing leaves the phone.
  A little less accurate.
- **Cloud captions.** Need internet and a key from a cloud service. See
  [Cloud services](cloud-services.md).
- **The home computer.** Faster and more accurate than the phone's model.
  It needs a computer that stays on.

It is worth it when the family has a suitable computer that is usually on,
and the phone is slow or gets warm.

## What the computer needs

In short: a Windows or Linux computer with an NVIDIA graphics card of at
least 6 GB, enough free disk space, and a way for the phone to reach it. It
must be awake while captions are wanted. The exact table is in the README
under [Home computer requirements](../README.md#home-computer-requirements).
The in-app guide describes a Windows computer. The Linux steps come from the
`server` folder.

## Setting it up on Windows

This is the same list the app shows under **How to set up the home
computer** (eich mechinim et ha-machshev ba-bayit).

1. **Send yourself the download link.** On the phone, open Settings, then
   the **Home computer** (ha-machshev ba-bayit) section, then
   **How to set up the home computer** (eich mechinim et ha-machshev ba-bayit).
   Tap **Send the download link** (shlichat kishur ha-horada) and send it
   to yourself by email or WhatsApp. Open it on the computer. One file,
   `Ozen-Home-Setup.cmd`, downloads.
2. **Double-click the file.** Windows asks for permission. Say yes. The
   setup first checks the graphics card and the free disk space. If they
   are not enough, it says so before downloading anything. Then it installs
   everything into `C:\ozen`. The app says this takes 10 to 30 minutes.
3. **Answer its questions.** It asks three things:
   - Whether to mark your Wi-Fi as a home network. Windows often calls
     home Wi-Fi "public", and then the phone cannot get in. Say yes only
     for your own home.
   - Whether to stop the computer going to sleep while it is plugged in.
     A sleeping computer cannot answer. The screen can still turn off.
   - Whether the phone should also work away from home. This installs
     Tailscale, a free service that gives the computer a safe address on
     the internet. A browser window opens once so you can sign in or make a
     free account.
4. **Scan the square code.** At the end, a page with a black-and-white
   square opens on the computer. On the iPhone, open the Camera, point it
   at the square, tap the Ozen link, and confirm. On Android, scan it with
   the phone's camera or a QR scanner, tap the link, and confirm; or type
   the address and code in by hand (see "Typing it in" below). To see the square again
   later, open the Start menu and choose "Ozen - pair a phone".

The first time the server starts, it downloads the speech model, so allow
about 10 more minutes before the first captions appear.

The setup also makes a pairing code (a secret password, kept in
`C:\ozen\pairing-code`), lets the phone through the Windows firewall on
home networks (port 8765 for the plain link and port 8766 for the encrypted
one; the two rules are called `Ozen server` and `Ozen server (encrypted)`),
and starts the server whenever Windows starts,
before anyone signs in. It starts it again if it stops. The log is
`C:\ozen\server.log`.

### Setting it up on Linux

This works on Linux, or on Windows through WSL (Ubuntu), and is for people
who are comfortable in a terminal. In the project's `server` folder run:

```
bash setup.sh
~/ozen-server/run.sh
```

`setup.sh` installs everything into `~/ozen-server`, makes a pairing code
once, prints it, and makes the QR page `pairing.html`. Scan it as in step 4.
To start the server with the computer, the
[server README](../server/README.md#starting-with-the-computer) gives a small
service file and these commands (the last line makes it start at boot,
before anyone signs in):

```
systemctl --user daemon-reload
systemctl --user enable --now ozen-server
sudo loginctl enable-linger "$USER"
```

On Windows, the setup adds two firewall rules for home networks, `Ozen server`
(port 8765) and `Ozen server (encrypted)` (port 8766). If the
computer has another firewall switched on, the
[server README](../server/README.md#reaching-it-from-the-phone) shows the rule
that lets the phone in.

## How the phone finds the computer

- **Same Wi-Fi.** The phone uses the computer's address on the home
  network, four numbers such as `192.168.1.20`. No extra program is needed.
- **From anywhere.** If you said yes to the away-from-home question,
  Tailscale Funnel gives the computer an address that starts with `wss://`.
  It works on any internet connection and is encrypted. The address is
  public, but useless without the pairing code. The QR code then carries
  this address.

Away from Wi-Fi it uses about 120 MB of mobile data an hour (the iPhone's
Settings text says cellular data).

If you said no, or Tailscale did not finish, the QR code carries the home
Wi-Fi address and works at home only. The Start menu item "Ozen - pair a
phone" makes the code again each time, so it stays right after a new router.

## Connecting the phone

### The easy way

On an iPhone, scan the QR code with the Camera and tap the Ozen link. On
Android, scan it with the phone's camera or a QR scanner and tap the Ozen
link (Android hands links that start with `ozen://` to Ozen). Either way the app asks
**Connect to the home computer?** (lehitchaber la-machshev ba-bayit?) and
tells you which computer the sound would go to. Tap **Connect**
(lehitchaber) only if it is your family's computer. The app then saves the
address and code, and switches the **Transcription engine** (manoa timlul)
to **Home computer** (ha-machshev ba-bayit).

If the app says the link did not come through, scan the square again, up
close.

### Typing it in

In Settings, under **Transcription engine** (manoa timlul), choose
**Home computer** (ha-machshev ba-bayit). Changing the engine restarts
listening. The **Home computer** section then appears below it.

1. In **Computer address** (ktovet ha-machshev), type the address from the
   pairing page, then tap **Save address** (shmirat ha-ktovet). A bare number
   like `192.168.1.20` is fine, and so is a `wss://` address. A `https://`
   address is turned into `wss://` for you. A computer out on the internet
   needs its `wss://` address: Ozen won't send the sound there unencrypted,
   and says so under the field.
2. In **The pairing code from the computer** (kod ha-tzimud me-ha-machshev),
   type the code. Tap **Save code** (shmirat ha-kod). Afterwards the row
   says **Pairing code saved on the phone** (kod tzimud shamur ba-telefon).

### Test connection

Once an address and a code are there, a button appears:
**Test connection** (bdikat chibur). It says hello to the computer the way
captions would, and times the answer. It tests what is on the screen, even
if you have not saved it yet.

| What it says | What it means | What to do |
| --- | --- | --- |
| "Connected: the computer answered in" a number of ms | The computer is there and accepted the code. | Nothing. |
| "The computer answered but didn’t accept the code." | The computer is on, but the code on the phone is not the one it expects. | Scan the QR code again, or type the code again. |
| "No answer from the computer." | The phone cannot reach it. | Check that it is on and awake, and on the same Wi-Fi as the phone (or online, for a `wss://` address). If it moved to a new network, scan its QR code again. On an iPhone, the message also says to check that Ozen is allowed under Settings › Privacy & Security › Local Network. |
| "The computer answered, but not as Ozen expected. Restart Ozen on the computer, or scan its QR code again." | The computer is on, but it answered with an error or with something the phone does not understand. It used to be reported as "No answer", which sent people to check a computer that was fine. | Restart Ozen on the computer, or scan the QR code again. |
| "The computer’s pairing changed. Scan its QR code again." | The computer has a new certificate (for example its certificate file was deleted), so the saved code no longer fits. | Scan the QR code again. |
| "The home computer can’t caption …" (with the language's name) | The speech model on the computer does not know the language the phone is set to. | See [When the computer cannot caption the language](#when-the-computer-cannot-caption-the-language). |
| "The address or code is missing" | One of the two is empty. | Fill in both. |
| "Outside the home network, use the address that starts with wss://" | The address is a plain one for a computer out on the internet. The phone won't send the sound, the code and the names there unencrypted. | Type the computer's address that starts with `wss://`. |

With such an address saved, the caption screen and the notice when
captions stop say the same thing, and a tap on the caption screen opens
Settings. Captions don't keep trying that address on their own: only the
`wss://` one helps.

### Speed or accuracy

The slider **Speed or accuracy** (mehirut mul diyuk) sets how many
wordings the computer weighs per sentence. The usual setting is 5 of 7, and
the difference in waiting time is a fraction of a second, so leave it.
**Back to the usual setting** (chazara la-hagdara ha-ragila) undoes a change.

## When the computer is off, asleep or out of reach

A sleeping computer answers nothing, just like an off one. What happens
then depends on whether the phone has its own model downloaded as a backup:

- **With the backup.** The phone's own Whisper model takes over by itself.
  Captions carry on. The phone keeps checking the computer and goes back to
  it once it answers. The status line says the phone carries on by
  itself.
- **Without the backup.** Captions stop and wait. The phone asks the
  computer again every 15 seconds and starts captions once it answers. The
  status line offers to add the backup. A backup that finishes downloading
  during the wait takes over by itself.

While captions wait, the microphone stays on for sound alerts only, so a
doorbell or smoke alarm still alerts you. Nothing is captioned or sent.

To get the backup, tap **Download a backup to the phone**
(horadat gibui la-telefon) in the **Home computer** (ha-machshev ba-bayit)
section. It downloads on Wi-Fi only, and needs enough free space on the
phone. Do it before you need it.

A wrong code is handled the same way: the phone carries on with its own
model if it has the backup. Without it, the screen says the home computer
did not accept the pairing code. Scan the QR code again.

When the computer drops out in the middle of a sentence and the backup is
downloaded, the phone's own model hears the last seconds again (up to about 20 seconds since the last
finished line) before it goes on with the live sound. A half-written line
that began inside those seconds is replaced by the replayed line, so words
do not show twice. If the replay brings no words, the half line stays.

Some answers are not "off or asleep", and the screen says so:

- **"The computer answered, but not as Ozen expected. Restart Ozen on the
  computer, or scan its QR code again."** The computer is on, but replied
  with an error or something the phone does not understand. The phone still
  tries again by itself, and carries on with its own model if it has the
  backup.
- **"The address or code is missing".** Nothing is saved to ask. Tap the
  line to open Settings and scan the QR code. Nothing is retried on a timer;
  captions start as soon as an address is saved. If sound alerts are on,
  they keep listening meanwhile.

## When the computer cannot caption the language

The computer's model knows some languages, not all. If the phone is set to
one it does not know, the screen says "The home computer can’t caption …"
with the language's name, and a tap opens the engine settings. Asking again
would get the same answer, so the phone does not ask on a timer.

- **With the backup**, the phone carries on with its own model, and the
  status line says "The home computer can’t caption …, so the phone carries
  on by itself".
- **Without it**, captions wait and say so. If sound alerts are on, they keep listening
  meanwhile, so a doorbell or smoke alarm still alerts.

To get captions, pick another engine under **Transcription engine**
(manoa timlul), or set the phone to a language the computer's model knows.
Test connection says the same words, so it can be checked there.

If the test finds no answer at home, see
[Troubleshooting](troubleshooting.md#the-home-computer-settings--home-computer--test-connection):
usually a sleeping computer, or Windows treating the Wi-Fi as "public".

## Keeping it updated

To update the server, run the setup again: double-click
`Ozen-Home-Setup.cmd` again, or run `bash setup.sh` again on Linux. This
updates the server and keeps the pairing code, so the phone does not need
to be paired again. On Linux, restart the service afterwards.

The speech models are not replaced by themselves. A newer version of a model
is fetched only if you delete its folder (`models--...`) and restart the
server. The [server README](../server/README.md) says where the folders are.

## Removing it

On the phone, in the **Home computer** (ha-machshev ba-bayit) section, tap
**Delete code** (mechikat ha-kod) and confirm. Captions go back to the
phone's own speech recognition until the QR code is scanned again. You can
also pick another engine under **Transcription engine** (manoa timlul).

On a Windows computer, the [server README](../server/README.md#removing-it)
lists the commands that remove the startup task, the two firewall rules and the
`C:\ozen` folder (run in PowerShell as administrator). Then delete "Ozen -
pair a phone" from the Start menu, and uninstall Tailscale from Windows
Settings, under Apps, if it was only there for Ozen.

On Linux, stop and disable the service, delete its service file and the
`~/ozen-server` folder (the server, its pairing code, its certificate and any
reports a phone sent), then let systemd forget the service:

```
systemctl --user disable --now ozen-server
rm ~/.config/systemd/user/ozen-server.service
rm -r ~/ozen-server
systemctl --user daemon-reload
```

If you installed the server into another folder by setting `OZEN_HOME`,
delete that folder instead of `~/ozen-server`.

If nothing else on the computer needs to start at boot before anyone signs
in, undo the last setup command too:

```
sudo loginctl disable-linger "$USER"
```

The speech models stay in `~/.cache/huggingface/hub`, in folders whose names
start with `models--`. Delete the ones Ozen used, if nothing else uses them:
`models--ivrit-ai--...` for the Hebrew models, and the general Whisper Turbo
if a phone was set to another language. If you added a firewall rule for the
phone, remove it as well, for example
`sudo ufw delete allow from 192.168.1.0/24 to any port 8765 proto tcp` (and
the same for port 8766).
