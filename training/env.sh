export HF_HUB_CACHE=${HF_HUB_CACHE:-$HOME/ozen-accuracy/hf/hub}
export LD_LIBRARY_PATH=$("$(dirname "${BASH_SOURCE[0]}")/venv/bin/python" -c "import os, nvidia.cublas, nvidia.cudnn; print(':'.join(os.path.join(list(m.__path__)[0], 'lib') for m in (nvidia.cublas, nvidia.cudnn)))")${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
