# A minute of Hebrew read aloud, for the Android device tests

Twelve Android device tests caption a recording: a lecture through the whole app,
live text during long speech, finished lines reaching the screen reader, and the
voice-note transcription screens. They look for `files/device-test/clip.wav` and
`clip.txt` on the phone and are skipped without them.

A run of the device tests by hand on GitHub makes them from a public-domain reading:

- **Audio:** LibriVox's recording of Yosef Haim Brenner's *In Winter* (בחורף), a
  story collection read in Hebrew by Omri Lernau, which archive.org marks public domain
  (`in_winter_ol_librivox`, file `inwinter_01_brenner_64kb.mp3`). The workflow
  downloads that file, checks its SHA-256, and cuts seconds 38.6 to 102.6: the
  opening of the first story, *There* (שמה), after LibriVox's spoken title.
- **Text:** `there.txt` is the same passage as the reader read it, from Project
  Ben-Yehuda's edition of the story (benyehuda.org/read/49), the source the LibriVox
  page names. Brenner died in 1921; the text is in the public domain.

The tests compare what the app wrote with `there.txt` (the lecture test allows 35% of
the words wrong); on the PC emulator all of them pass with this reading.
