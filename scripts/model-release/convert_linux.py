import json
import os
import shutil
import sys
import time

wkt_root, model, out = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
os.environ["TEST_WHISPER_VERSION"] = model
os.environ["TEST_CACHE_DIR"] = out
os.environ["TEST_DEV"] = "cpu"
sys.path.insert(0, wkt_root)

import numpy as np
import coremltools as ct
from huggingface_hub import hf_hub_download
from argmaxtools import _sdpa
from argmaxtools import test_utils as atu


class AnyOutput(dict):
    def __getitem__(self, key):
        return np.zeros((1,), dtype=np.float16)


def fake_predict(self, data=None, state=None, **kwargs):
    return AnyOutput()


ct.models.MLModel.predict = fake_predict
atu.TEST_COMPILE_COREML = False

from tests import test_text_decoder as ttd
from tests import test_audio_encoder as tae
from whisperkit import text_decoder, audio_encoder


def model_file(name):
    return os.path.join(model, name) if os.path.isdir(model) else hf_hub_download(repo_id=model, filename=name)


for filename in ["config.json", "generation_config.json"]:
    shutil.copy(model_file(filename), os.path.join(out, filename))
token_timestamps = bool(json.load(open(os.path.join(out, "generation_config.json"))).get("alignment_heads"))
print(f"token timestamps: {token_timestamps}", flush=True)

ttd.TEST_WHISPER_VERSION = model
ttd.TEST_CACHE_DIR = out
ttd.TEST_TOKEN_TIMESTAMPS = token_timestamps
text_decoder.SDPA_IMPL = _sdpa.Cat
tae.TEST_WHISPER_VERSION = model
tae.TEST_CACHE_DIR = out
audio_encoder.SDPA_IMPL = _sdpa.SplitHeadsQ

for cls in (ttd.TestWhisperTextDecoder, tae.TestWhisperMelSpectrogram, tae.TestWhisperAudioEncoder):
    started = time.time()
    done = os.path.join(out, f"{cls.__name__}.done")
    if os.path.exists(done):
        print(f"{cls.__name__}: already converted", flush=True)
        continue
    print(f"{cls.__name__}: converting...", flush=True)
    cls.setUpClass()
    atu._save_coreml_asset(cls.test_coreml_model, out, cls.model_name, do_compile=False, do_remove_mlpackage=False)
    cls.tearDownClass()
    open(done, "w").write("ok\n")
    print(f"{cls.__name__}: saved in {time.time() - started:.0f}s", flush=True)

print("done", out, flush=True)
