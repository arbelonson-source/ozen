# Captions from a computer at home

Ozen can use a computer at home to write the captions. The phone sends the
sound there and gets the words back. It is optional. The phone works fine
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
   at the square, tap the Ozen link, and confirm. To see the square again
   later, open the Start menu and choose "Ozen - pair a phone".

The first start downloads the speech model. Give it about 10 minutes
before the first captions.

The setup also makes a pairing code (a secret password, kept in
`C:\ozen\pairing-code`), lets the phone through the Windows firewall on
home networks on port 8765, and starts the server whenever Windows starts,
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
To start the server with the computer, the server README gives a small
service file and these commands (the last line makes it start at boot,
before anyone signs in):

```
systemctl --user daemon-reload
systemctl --user enable --now ozen-server
sudo loginctl enable-linger "$USER"
```

If the computer has a firewall switched on, the server README shows the rule
that lets the phone in.

## How the phone finds the computer

- **Same Wi-Fi.** The phone uses the computer's address on the home
  network, four numbers such as `192.168.1.20`. No extra program is needed.
- **From anywhere.** If you said yes to the away-from-home question,
  Tailscale Funnel gives the computer an address that starts with `wss://`.
  It works on any internet connection and is encrypted. The address is
  public, but useless without the pairing code. The QR code then carries
  this address.

If you said no, or Tailscale did not finish, the QR code carries the home
Wi-Fi address and works at home only. The Start menu item "Ozen - pair a
phone" makes the code again each time, so it stays right after a new router.

## Connecting the phone

### The easy way

Scan the QR code with the iPhone Camera and tap the Ozen link. The app asks
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
| "No answer from the computer." | The phone cannot reach it. | Check that it is on and awake, and on the same Wi-Fi as the phone (or online, for a `wss://` address). |
| "The address or code is missing" | One of the two is empty. | Fill in both. |

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
  it once it answers. The status line says it is carrying on with the phone's
  own recognition.
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
server. The server README says where the folders are.

## Removing it

On the phone, in the **Home computer** (ha-machshev ba-bayit) section, tap
**Delete code** (mechikat ha-kod) and confirm. Captions go back to the
phone's own speech recognition until the QR code is scanned again. You can
also pick another engine under **Transcription engine** (manoa timlul).

On a Windows computer, the server README lists the commands that remove
the startup task, the firewall rule and the `C:\ozen` folder (run in
PowerShell as administrator). Then delete "Ozen - pair a phone" from the
Start menu, and uninstall Tailscale from Windows Settings, under Apps, if
it was only there for Ozen.

On Linux, stop and disable the service, then delete the `~/ozen-server`
folder and the service file:

```
systemctl --user disable --now ozen-server
```
