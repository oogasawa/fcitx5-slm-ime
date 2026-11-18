#!/bin/bash
#SBATCH --job-name=slm-ime-units
#SBATCH --partition=b200
#SBATCH --account=b200
#SBATCH --gres=gpu:1
#SBATCH --cpus-per-task=32
#SBATCH --mem=400G
#SBATCH --time=36:00:00
#SBATCH --output=%x-%j.log
# One training cycle entirely on the B200 node, nothing leaves NIG:
#   1. the readings of the chunks not cleaned yet are cleaned (clean_readings.py; the cleaned chunks are kept in
#      ~/slm-ime-prep/clean and used again by the next cycle),
#   2. the rows are cut and given hints with the arguments of the units 1 to 2 (--partial-start-percent 20 too),
#   3. the model is trained with the arguments of the big model (8 epochs, batch 1024, d512, 6+6 layers).
# The chunks are the first N lines of the order file of the reading job (the units follow each other in it:
# 22068 = unit 1, 44136 = units 1 to 2, 66204 = units 1 to 3, 90000 = units 1 to 4, 100082 = units 1 to 5).
# PREP_ONLY=1 (with bash, not sbatch) runs the steps 1 and 2 only, to test them.
# Usage (from at022vm02):  sbatch --export=ALL,N=44136,RUN=u12 ~/slm-ime-prep/train_units.sbatch
# More arguments of train.py in TRAIN_EXTRA, e.g. TRAIN_EXTRA="--amp tf32 --diag-log diag.tsv" (bf16 diverged in units 1-3).
# Several GPUs (train.py with DistributedDataParallel):  sbatch --gres=gpu:4 --export=ALL,N=90000,RUN=u1234 ...
# train.py resumes from checkpoints/<RUN>/last.pt, so a job that ran out of time can be submitted again; the steps 1
# and 2 are then skipped when their results are there (data_hints/done).
set -euo pipefail
: "${N:?the number of chunks (lines of the order file) is not set}"
: "${RUN:?the name of the run is not set}"
P=$HOME/slm-ime-prep
W=$HOME/kkime-b200/wiki
PY=$P/venv/bin/python
log() { echo "$(date '+%F %T') $*"; }
cd "$P"
OUT=$P/runs/$RUN
mkdir -p "$P/clean" "$OUT"

if [ ! -e "$OUT/data_hints/done" ]; then
  log "run $RUN: the first $N chunks of the order file"
  rm -rf "$OUT/pairs" "$OUT/todo" "$OUT/data" "$OUT/data_hints" && mkdir -p "$OUT/pairs" "$OUT/todo"
  missing=0; new=0
  while read -r f; do
    b=$(basename "$f" .tsv)
    if [ ! -s "$W/wiki_pairs/${b}_pairs.tsv" ]; then missing=$((missing+1)); continue; fi
    if [ ! -s "$P/clean/${b}_pairs.tsv" ]; then ln -s "$W/wiki_pairs/${b}_pairs.tsv" "$OUT/todo/${b}_pairs.tsv"; new=$((new+1)); fi
  done < <(head -n "$N" "$W/order_pod.txt")
  log "chunks missing in the reading output: $missing; chunks to clean: $new"
  if [ "$missing" -ne 0 ]; then log "ABORT: the reading of these chunks is not finished"; exit 3; fi

  if [ "$new" -gt 0 ]; then
    log "cleaning the readings of $new chunks"
    nice -n 5 "$PY" code/tools/clean_readings.py "$OUT/todo" "$OUT/clean_new" --kanjidic dict/kanjidic2.xml.gz \
        --jmdict dict/jmdict_pairs.tsv --checker code/tools --workers 32 | tail -n 3
    find "$OUT/clean_new" -name '*_pairs.tsv' -print0 | xargs -0 mv -t "$P/clean/"   # not a glob: 40000 names are too long for one command
    mv "$OUT/clean_new/changes.tsv" "$P/clean/changes-$RUN.tsv" 2>/dev/null || true
  fi
  while read -r f; do
    b=$(basename "$f" .tsv)
    [ -s "$P/clean/${b}_pairs.tsv" ] || { log "ABORT: no cleaned chunk for $b"; exit 4; }
    ln -s "$P/clean/${b}_pairs.tsv" "$OUT/pairs/${b}_pairs.tsv"
  done < <(head -n "$N" "$W/order_pod.txt")
  log "building the rows from $(ls "$OUT/pairs" | wc -l) chunks"
  nice -n 5 "$PY" code/prep/build_dataset_g.py "$OUT/pairs" "$OUT/data" --context-chars 100 --empty-percent 20 \
      --partial-end-percent 20 --partial-start-percent 20 --hiragana-source
  cp dict/stand-in-tokenizer.json "$OUT/data/tokenizer.json"   # add_hints.py copies the tokenizer of its input; train.py builds a new one
  log "hints"
  nice -n 5 "$PY" code/prep/add_hints.py dict/hard_words2b.tsv "$OUT/data" "$OUT/data_hints" \
      --history-table dict/common_words3.tsv --history-percent 60 --workers 24
  rm -f "$OUT/data/tokenizer.json" "$OUT/data_hints/tokenizer.json"
  wc -l "$OUT"/data_hints/*.tsv
  touch "$OUT/data_hints/done"
fi

if [ -n "${PREP_ONLY:-}" ]; then log "PREP_ONLY: the steps 1 and 2 are done, no training"; exit 0; fi
log "training"
nvidia-smi --query-gpu=name,memory.total --format=csv,noheader
cd "$HOME/slm-ime-train"
# One process per GPU given to the job (sbatch --gres=gpu:N): torchrun for two or more, python for one. The batch
# stays 1024 rows per step over all GPUs, so the runs can be compared whatever the number of GPUs.
NGPU=$(nvidia-smi -L | wc -l)
if [ "$NGPU" -gt 1 ]; then
  awk "/DistributedDataParallel/ {f = 1} END {exit !f}" train.py || { log "train.py here cannot use several GPUs"; exit 1; }
  LAUNCH=(venv/bin/torchrun --standalone --nproc_per_node="$NGPU")
else
  LAUNCH=(venv/bin/python -u)
fi
log "$NGPU GPU(s): ${LAUNCH[*]}"
"${LAUNCH[@]}" train.py --data-dir "$OUT/data_hints" --run-name "$RUN" --epochs 8 --batch-size 1024 --lr 0.001 \
    --num-workers 24 --d-model 512 --nhead 8 --enc-layers 6 --dec-layers 6 --d-ff 2048 --dropout 0.1 --max-len 192 \
    ${TRAIN_EXTRA:-}
log "done"
