#!/usr/bin/env bash
# One worker: takes the next chunk from the order file, claims it, and runs the kana-kanji pair workflow on it.
# Usage: worker.sh <broker chat URL> <tag> <model> <order file>     (data under $DATA, default /data)
set -u
VURL="$1"; TAG="$2"; VMODEL="$3"; ORDER="$4"
DATA="${DATA:-/data}"
# Small resident size (about 70MB each): interpreter only, small heap and stacks. The work is waiting for the GPU.
J="java -Xmx96m -Xms8m -Xmn24m -XX:+UseSerialGC -Xint -XX:-UsePerfData -Xss256k -XX:ReservedCodeCacheSize=8m"
OUT="$DATA/wiki_pairs"; CLAIM="$DATA/wiki_claims"
JAR=/opt/kkime/jars/turing-workflow.jar; YAML=/opt/kkime/kana-kanji-pairs.yaml
mkdir -p "$OUT" "$CLAIM"; ts(){ date "+%Y-%m-%d %H:%M:%S"; }
echo "[$(ts)] [$TAG] start vllm=$VURL model=$VMODEL order=$ORDER"
made=0; failed=0; consec=0
while read -r f; do
  [ -e "$f" ] || continue
  b=$(basename "$f" .tsv)
  [ -e "$OUT/${b}_pairs.tsv" ] && continue
  mkdir "$CLAIM/$b" 2>/dev/null || continue
  T="$OUT/${b}_pairs.tsv.$TAG.tmp"
  echo "[$(ts)] [$TAG] START $b"
  # A hung run is ended after four hours and counted as a failure.
  timeout 14400 $J -jar "$JAR" run -w "$YAML" -P ocr.file="$f" -P pairs.file="$T" -P vllm.url="$VURL" -P vllm.model="$VMODEL" > /dev/null 2>&1
  rc=$?
  lc=0; [ -e "$T" ] && lc=$(wc -l < "$T")
  if [ $rc -eq 0 ] && [ "$lc" -gt 1 ]; then
    mv "$T" "$OUT/${b}_pairs.tsv"; rmdir "$CLAIM/$b" 2>/dev/null; made=$((made+1)); consec=0
    echo "[$(ts)] [$TAG] OK $b rows=$lc"
  else
    rm -f "$T"; rmdir "$CLAIM/$b" 2>/dev/null; failed=$((failed+1)); consec=$((consec+1))
    echo "[$(ts)] [$TAG] FAIL $b rc=$rc lines=$lc consec=$consec"
    [ $consec -ge 3 ] && { echo "[$(ts)] [$TAG] ABORT 3 consecutive fails"; exit 2; }
  fi
done < "$ORDER"
echo "[$(ts)] [$TAG] end made=$made failed=$failed"
