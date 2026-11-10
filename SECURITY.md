# Security

## Reporting a vulnerability

Please report it privately, not in a public issue: on this repository's
**Security** tab, choose **Report a vulnerability**
([direct link](https://github.com/arbelonson-source/ozen/security/advisories/new)).

Say which part is affected (the iPhone app, the Android app, the home computer
server, or the website), how to reproduce it, and what someone could do with it.

## What matters most

- **The home computer server** (`server/`) listens on the network. With
  Tailscale Funnel it can be reached from the internet. It accepts only a phone
  that sends the pairing code, compared in constant time, and refuses
  everything else.
- **Sound and words** stay on the phone unless the person sets up cloud
  captions, a home computer, or Apple's servers for Apple's engine
  ([where your words go](docs/privacy.md)).
- **Keys and pairing codes** are kept in the iPhone's Keychain and Android's
  Keystore.

## Supported versions

Fixes go into the next release of each app and into the home server on
`master`.
