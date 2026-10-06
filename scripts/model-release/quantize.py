import sys
import time

import coremltools as ct
from coremltools.optimize.coreml import OpPalettizerConfig, OptimizationConfig, palettize_weights

src, dst, nbits = sys.argv[1], sys.argv[2], int(sys.argv[3])
mode = sys.argv[4] if len(sys.argv) > 4 else "uniform"
started = time.time()
model = ct.models.MLModel(src, skip_model_load=True)
config = OptimizationConfig(global_config=OpPalettizerConfig(nbits=nbits, mode=mode, weight_threshold=2048))
palettize_weights(model, config).save(dst)
print(f"{src} -> {dst}: {nbits}-bit {mode} in {time.time() - started:.0f}s", flush=True)
