#!/usr/bin/env bash
# Starts $WORKERS workers in this Pod. The order file is the data's order list, last chunk first,
# so that these Pods and the workers on MiniPC (which go from the front) do not meet until the end.
set -u
DATA="${DATA:-/data}"; WORKERS="${WORKERS:-20}"
ORDER="$DATA/order_pod.txt"
[ -e "$ORDER" ] || { echo "missing $ORDER"; exit 1; }
pids=()
for i in $(seq 1 "$WORKERS"); do
  /opt/kkime/worker.sh "$VURL" "${HOSTNAME}-$i" "$VMODEL" "$ORDER" >> "$DATA/logs/${HOSTNAME}-$i.log" 2>&1 &
  pids+=($!); sleep 1
done
fail=0
for p in "${pids[@]}"; do wait "$p" || fail=1; done
exit $fail
