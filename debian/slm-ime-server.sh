#!/bin/sh
# Starts the conversion server of fcitx5-slm-ime on 127.0.0.1:8190.
# The choices of the user are kept in $XDG_DATA_HOME/slm-ime/user_history.tsv.
set -e
data="${XDG_DATA_HOME:-$HOME/.local/share}/slm-ime"
mkdir -p "$data"
cd "$data"
# The PyTorch native libraries are in the package; DJL does not download them.
export DJL_CACHE_DIR=/usr/lib/slm-ime-server/djl-cache
model=/usr/share/slm-ime/model
# The Java runtime of the package (made by jlink). The libjvmcicompiler library is not in it.
java=/usr/lib/slm-ime-server/runtime/bin/java
# On aarch64 the cores are of two kinds, and PyTorch with a thread on every core is slower than with a few:
# 0.85 s per conversion with 20 threads and 0.13 s with 8 threads on a machine with 20 cores.
if [ "$(uname -m)" = aarch64 ]; then
    export OMP_NUM_THREADS="${OMP_NUM_THREADS:-8}"
fi
# shellcheck disable=SC2086
exec "$java" -XX:+IgnoreUnrecognizedVMOptions -XX:-UseJVMCICompiler --enable-native-access=ALL-UNNAMED -Xmx2g \
    -Dmodel.context=true -Dmodel.dir="$model" -Dtokenizer="$model/tokenizer.json" \
    -Dhistory.file="$data/user_history.tsv" ${SLM_IME_JAVA_OPTS} \
    -jar /usr/share/java/slm-ime-server.jar --server 8190
