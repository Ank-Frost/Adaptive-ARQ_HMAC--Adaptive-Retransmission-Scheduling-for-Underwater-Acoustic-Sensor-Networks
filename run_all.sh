#!/bin/bash
# Runs the full comparison (3 conditions x 4 loads x 10 seeds x 2 protocols = 240 simulations)
# and then computes the statistics and figures.
#
# The work is split into 6 UnetStack processes (3 conditions x 2 halves of the seeds), JOBS of them
# at a time. Each process needs about 1-1.5 GB of memory; lower JOBS on a small machine.
# Usage:  ./run_all.sh            (from this folder)
#         JOBS=2 ./run_all.sh
set -e
cd "$(dirname "$0")"
JOBS=${JOBS:-4}
UNET=unet-7.0.0/bin/unet

if [ ! -x "$UNET" ]; then
  echo "UnetStack not found at $UNET - see README.md (Requirements)." >&2
  exit 1
fi

mkdir -p results
echo "Running 240 simulations with $JOBS parallel processes (progress: tail -f results/log_*.txt)"
for c in Static-400m Mobile-400m Mobile-3km; do
  for s in 1-5 6-10; do
    echo "$c $s"
  done
done | xargs -P "$JOBS" -L 1 sh -c \
  'ONLY_CONDITION=$0 ONLY_SEEDS=$1 '"$UNET"' run_comparison.groovy 2>&1 | grep -v "Simulation #" > results/log_$0_$1.txt'

echo "Simulations finished. Computing statistics and figures..."
python3 plot_results.py
