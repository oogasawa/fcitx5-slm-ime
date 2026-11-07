#!/usr/bin/env bash
# Makes the conversion model from the raw corpora, the way the model of the releases was made: the Japanese Wikipedia
# and FineWeb-2 are cut into chunks, a large language model writes the reading and the phrasal unit (bunsetsu)
# boundaries of every sentence, the readings are checked against dictionaries, and the small model is trained on the
# pairs and exported for the engine.
#
# Usage: prep/train_from_scratch.sh [stage...]      (no stage: all of them, in order)
#   stages: tools corpora chunks pairs dicts clean dataset train export
# Every stage reads what the stages before it wrote under $WORK and is skipped when its output is there, so the script
# can be run again after a failure. Settings, from the environment:
#   WORK        the directory of everything made (default ./train-work)
#   LLM_URL     the chat completions URL of an OpenAI-compatible server that serves LLM_MODEL, for the stage pairs,
#               e.g. http://localhost:8000/v1/chat/completions (vLLM). The only stage that needs GPUs.
#   LLM_MODEL   default google/gemma-4-26B-A4B-it, the model that wrote the pairs of the releases
#   WORKERS     how many chunks are sent to the LLM server at a time (default 20)
#   LLM_BACKGROUND=1  when LLM_URL is a GPU broker (quarkus-gpu-broker) shared with others: the requests are marked
#               as background work, which the broker serves after interactive requests
#   WIKI_DUMP   the date of the Wikipedia dump (default latest; the releases used a dump of October 2026)
#   SMOKE=1     a trial run on a few chunks and a tiny model, to check the stages from end to end in an hour
# Needs: bash, curl, git, Python 3 with requirements.txt, a JDK 21 or later (GraalVM CE 25 was used) and Maven, and a
# GPU for the stage train (it runs on a CPU as well, but the full training would take months there).
#
# The result is not the released model bit for bit: train.py does not fix its random seed, the LLM server's answers
# depend on its build, and the second FineWeb-2 sample of the releases was drawn with a seed that was not recorded.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mkdir -p "${WORK:-$PWD/train-work}" && cd "${WORK:-$PWD/train-work}" && pwd)
LLM_MODEL=${LLM_MODEL:-google/gemma-4-26B-A4B-it}
WORKERS=${WORKERS:-20}
WIKI_DUMP=${WIKI_DUMP:-latest}
SMOKE=${SMOKE:-0}
M2="$WORK/tools/m2"
log() { echo "[$(date '+%F %T')] $*"; }

# The checker of the readings, at the commit that checked the pairs of the releases
ANTHY_DICTS_REPO=${ANTHY_DICTS_REPO:-https://github.com/oogasawa/Anthy-dicts}
ANTHY_DICTS_COMMIT=082d7b53eb3a341675f9e55609c680aafd9c4d1c
COMMONS_COMPRESS=1.27.1
COMMONS_IO=2.18.0

stage_tools() {
    local T="$WORK/tools"
    [ -e "$T/done" ] && return
    mkdir -p "$T"
    log "tools: the jars of the Wikipedia extractor"
    curl -fsSL -o "$T/commons-compress.jar" \
        "https://repo1.maven.org/maven2/org/apache/commons/commons-compress/$COMMONS_COMPRESS/commons-compress-$COMMONS_COMPRESS.jar"
    curl -fsSL -o "$T/commons-io.jar" \
        "https://repo1.maven.org/maven2/commons-io/commons-io/$COMMONS_IO/commons-io-$COMMONS_IO.jar"
    log "tools: the program that has the language model write the pairs (pairs/, on POJO-actor)"
    (cd "$ROOT/pairs" && mvn -B -q -Dmaven.repo.local="$M2" package)
    log "tools: Anthy-dicts (the checker of the readings)"
    rm -rf "$T/Anthy-dicts"
    git clone -q "$ANTHY_DICTS_REPO" "$T/Anthy-dicts"
    git -C "$T/Anthy-dicts" checkout -q "$ANTHY_DICTS_COMMIT"
    touch "$T/done"
}

stage_corpora() {
    local C="$WORK/corpora"
    mkdir -p "$C"
    if [ ! -e "$C/jawiki-pages-articles.xml.bz2" ]; then
        log "corpora: the Japanese Wikipedia ($WIKI_DUMP)"
        # A trial run reads only the first articles: the first 80 MB of the dump hold them
        local range=()
        [ "$SMOKE" = 1 ] && range=(--range 0-80000000)
        curl -fSL "${range[@]}" -o "$C/jawiki-pages-articles.xml.bz2.part" \
            "https://dumps.wikimedia.org/jawiki/$WIKI_DUMP/jawiki-$WIKI_DUMP-pages-articles.xml.bz2"
        mv "$C/jawiki-pages-articles.xml.bz2.part" "$C/jawiki-pages-articles.xml.bz2"
    fi
    # FineWeb-2 is read from Hugging Face by the stage chunks, a few columns of its parquet files at a time.
}

stage_chunks() {
    local T="$WORK/tools" C="$WORK/corpora" K="$WORK/chunks"
    mkdir -p "$K"
    if [ ! -e "$K/wiki.done" ]; then
        log "chunks: the paragraphs of the Wikipedia articles"
        local most=""
        [ "$SMOKE" = 1 ] && most=3000
        java -cp "$T/commons-compress.jar:$T/commons-io.jar" -Djdk.xml.maxGeneralEntitySizeLimit=0 \
            -Djdk.xml.totalEntitySizeLimit=0 "$ROOT/wikipedia/WikiTextExtractor.java" \
            "$C/jawiki-pages-articles.xml.bz2" $most > "$K/jawiki_text.tsv"
        # 200 paragraphs (pages) to a chunk, as for the releases
        rm -rf "$K/wiki" && java "$ROOT/wikipedia/WikiToChunks.java" "$K/jawiki_text.tsv" "$K/wiki" 0 200
        touch "$K/wiki.done"
    fi
    if [ ! -e "$K/fineweb.done" ]; then
        log "chunks: two samples of FineWeb-2 (Japanese)"
        rm -rf "$K/fineweb" "$K/fineweb2"
        if [ "$SMOKE" = 1 ]; then
            python3 "$ROOT/fineweb/make_chunks.py" "$K/fineweb" --max-files 1 --target-sentences 900
        else
            python3 "$ROOT/fineweb/make_chunks.py" "$K/fineweb"
            # The second sample of the releases: other sentences, numbered from 10000. Its seed was not recorded.
            python3 "$ROOT/fineweb/make_chunks.py" "$K/fineweb2" --first-chunk 10000 --exclude "$K/fineweb" --seed 2
        fi
        touch "$K/fineweb.done"
    fi
    # The chunks the LLM reads: for the releases, the Wikipedia chunks whose number ends in 0 or 5 (a tenth of
    # them each), and every FineWeb-2 chunk
    if [ "$SMOKE" = 1 ]; then
        { ls "$K"/wiki/wiki_*.tsv | awk 'NR <= 2'; ls "$K"/fineweb/fw_*.tsv | awk 'NR <= 2'; } > "$K/order.txt"
    else
        { ls "$K"/wiki/wiki_*.tsv | awk '/[05]\.tsv$/'; ls "$K"/fineweb*/fw_*.tsv; } > "$K/order.txt"
    fi
    log "chunks: $(wc -l < "$K/order.txt") chunks for the LLM"
}

stage_pairs() {
    local P="$WORK/pairs"
    : "${LLM_URL:?set LLM_URL to the chat completions URL of the LLM server}"
    mkdir -p "$P"
    log "pairs: $WORKERS chunks at a time against $LLM_URL ($LLM_MODEL)"
    local background=()
    [ "${LLM_BACKGROUND:-0}" = 1 ] && background=(-Dpairs.background=true)
    java "${background[@]}" -jar "$ROOT/pairs/target/slm-ime-pairs.jar" "$LLM_URL" "$LLM_MODEL" "$WORKERS" "$WORK/chunks/order.txt" \
        "$P/pairs" > "$P/make_pairs.log" 2>&1
    log "pairs: $(ls "$P"/pairs/*_pairs.tsv | wc -l) of $(wc -l < "$WORK/chunks/order.txt") chunks made"
}

stage_dicts() {
    local D="$WORK/dicts"
    mkdir -p "$D"
    if [ ! -e "$D/kanjidic2.xml.gz" ]; then
        log "dicts: KANJIDIC2"
        curl -fsSL -o "$D/kanjidic2.xml.gz" http://www.edrdg.org/kanjidic/kanjidic2.xml.gz
    fi
    if [ ! -e "$D/jmdict_pairs.tsv" ]; then
        log "dicts: JMdict and JMnedict"
        python3 "$ROOT/prep/download_jmdict.py" --out "$D/jmdict_pairs.tsv"
    fi
}

stage_clean() {
    local T="$WORK/tools" D="$WORK/dicts"
    [ -e "$WORK/clean/done" ] && return
    log "clean: the readings checked against the dictionaries and Sudachi"
    rm -rf "$WORK/clean"
    python3 "$ROOT/prep/clean_readings.py" "$WORK/pairs/pairs" "$WORK/clean" --kanjidic "$D/kanjidic2.xml.gz" \
        --jmdict "$D/jmdict_pairs.tsv" --checker "$T/Anthy-dicts/tools" --workers "$(nproc)"
    touch "$WORK/clean/done"
}

stage_dataset() {
    [ -e "$WORK/data/train.tsv" ] && return
    log "dataset: the training rows"
    python3 "$ROOT/prep/build_dataset_g.py" "$WORK/clean" "$WORK/data" --context-chars 100 --partial-end-percent 20 \
        --hiragana-source
}

stage_train() {
    local size=(--epochs 8 --batch-size 64 --d-model 512 --nhead 8 --enc-layers 6 --dec-layers 6 --d-ff 2048 --max-len 192)
    [ "$SMOKE" = 1 ] && size=(--epochs 1 --batch-size 16 --d-model 64 --nhead 4 --enc-layers 1 --dec-layers 1 --d-ff 128
        --max-len 192 --amp none)
    log "train: ${size[*]}"
    (cd "$ROOT" && python3 -u train.py --data-dir "$WORK/data" --run-name from-scratch "${size[@]}")
}

stage_export() {
    local M="$WORK/model" last
    mkdir -p "$M"
    # The releases are the weights after the last epoch (epoch 8), not those of the lowest validation loss
    last=$(ls "$ROOT"/checkpoints/from-scratch/epoch_*.pt | sort | tail -1)
    log "export: $last"
    python3 "$ROOT/export_weights.py" "$last" "$M/slm-ime-weights.bin" 100
    cp "$WORK/data/tokenizer.json" "$M/tokenizer.json"
    log "export: $M holds slm-ime-weights.bin and tokenizer.json, the files of /usr/share/slm-ime/model"
}

stages=("$@")
[ ${#stages[@]} -eq 0 ] && stages=(tools corpora chunks pairs dicts clean dataset train export)
for s in "${stages[@]}"; do
    "stage_$s"
done
