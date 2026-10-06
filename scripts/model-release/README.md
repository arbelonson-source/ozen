# Publishing a Whisper model as release assets

WhisperKit's model hub only carries OpenAI's own Whisper. A model trained
elsewhere (ivrit.ai's Hebrew Whisper) is published as the assets of a
GitHub release of the public `arbelonson-source/ozen-models` repository
instead, and the app downloads it from there (`ReleaseModelDownloader`).
It lives apart from the code so the phone downloads without signing in
even while this repository is private.

1. Convert the checkpoint to WhisperKit's Core ML layout and compress it,
   on Linux, with Argmax's `whisperkittools` (commit 84f77a8, 2026-02-20):

   ```
   git clone https://github.com/argmaxinc/whisperkittools
   uv venv wkt
   VIRTUAL_ENV=$PWD/wkt uv pip install "torch==2.5.0" --index-url https://download.pytorch.org/whl/cpu
   VIRTUAL_ENV=$PWD/wkt uv pip install -e ./whisperkittools
   wkt/bin/python convert_linux.py whisperkittools <hub id or local folder> converted
   PYTHON=wkt/bin/python ./package.sh converted <folder-name> 8 uniform
   ```

   `convert_linux.py` runs whisperkittools' own converters with the two
   macOS-only steps left out (a Core ML prediction and `xcrun`'s compile),
   so it leaves `.mlpackage` bundles; the phone compiles them on first
   use. A local folder is a checkpoint saved by `training/train_encoder.py`.
   `package.sh` palettizes the decoder's and encoder's weights to 8 bits,
   copies the mel front end as it is, and does step 2. The folder name is
   the one the catalog will give as `folderName`.
2. `manifest.py pack <model-folder> <assets>` flattens the folder into
   assets and writes `manifest.json` (path, asset, size, SHA-256).
3. `gh release create <tag> -R arbelonson-source/ozen-models --title ... --notes ... <assets>/*` uploads
   them. One release per model version; the catalog names the tag.
4. Run the "Verify model release" workflow with that tag. It rebuilds the
   folder on a Mac, compiles it, transcribes the five clips in `clips/`
   with WhisperKit and scores them (`wer.py`).
5. Add the model to `WhisperModelCatalog` with `source: .ozenRelease(tag:)`
   and the folder name the release was packed from.

The clips are from Google's FLEURS Hebrew test set (CC BY 4.0), with their
transcripts in `refs.jsonl`.
