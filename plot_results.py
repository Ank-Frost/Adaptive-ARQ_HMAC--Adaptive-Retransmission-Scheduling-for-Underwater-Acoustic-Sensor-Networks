#!/usr/bin/env python3
"""Statistics and figures for ARQ_HMAC (Dao & Tran, 2025) vs the proposed Adaptive ARQ_HMAC.

Reads results/runs_*.csv and results/estimate_trace.csv (written by run_comparison.groovy) and writes:
  results/statistics.txt              Proposed vs ARQ_HMAC: mean, change, paired Wilcoxon p-value
  results/fig_<condition>.png         PDR, throughput, retransmissions, collision rate, delay vs load
  results/fig_estimate_tracking.png   delay value in use vs the true delay, over time

Every (condition, load, seed) runs both protocols on the same traffic, so runs are compared in pairs
(Wilcoxon signed-rank test). A difference is called significant only if p < 0.05.

Usage (from this folder):  python3 plot_results.py
"""
import csv
import glob
import os
from collections import defaultdict
from statistics import mean, stdev

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.stats import wilcoxon

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
BASELINE = "ARQ_HMAC"
ALPHA_SIG = 0.05

# Colorblind-safe categorical colors (validated), plus line style and marker for print/greyscale
STYLE = {
    "ARQ_HMAC": dict(color="#2a78d6", linestyle="-",  marker="o"),
    "Proposed": dict(color="#eb6834", linestyle="--", marker="s"),
}
TRACKED = ["ARQ_HMAC", "Proposed"]
TEXT, TEXT_2, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"

# (column, label, higher is better)
METRICS = [
    ("pdr",        "PDR (%)",               True),
    ("throughput", "Throughput S",          True),
    ("retx",       "Retransmissions",       False),
    ("collRate",   "Collision rate (%)",    False),
    ("delay",      "End-to-end delay (s)",  False),
    ("timeouts",   "ACK timeouts",          False),
    ("estErr",     "Delay est. error (ms)", False),
]
PLOTTED = METRICS[:5]

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": TEXT_2, "text.color": TEXT,
    "xtick.color": TEXT_2, "ytick.color": TEXT_2, "font.size": 10,
    "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "axes.grid.axis": "y", "grid.color": GRID, "grid.linewidth": 0.8,
})


def read_runs():
    rows = []
    for path in sorted(glob.glob(os.path.join(RESULTS, "runs_*.csv"))):
        with open(path) as f:
            rows += list(csv.DictReader(f))
    for r in rows:
        r["load"] = float(r["load"])
        r["seed"] = int(r["seed"])
        for key, _, _ in METRICS:
            r[key] = float(r[key])
    # One row per (condition, load, seed, protocol). The same run may appear in two files (e.g. a full run
    # and a split run), but only with identical numbers; otherwise the files come from different code.
    unique = {}
    for r in rows:
        key = (r["condition"], r["load"], r["seed"], r["protocol"])
        if key in unique and any(unique[key][k] != r[k] for k, _, _ in METRICS):
            raise SystemExit(f"Conflicting results for {key} in different results/runs_*.csv files. "
                             "They come from different versions of the code: delete the outdated files and rerun.")
        unique[key] = r
    return list(unique.values())


def ordered(values, order):
    return [v for v in order if v in values] + sorted(v for v in values if v not in order)


# ------------------------------------------------------------------ statistics
def statistics(rows):
    conditions = list(dict.fromkeys(r["condition"] for r in rows))
    protocols = ordered({r["protocol"] for r in rows}, list(STYLE))
    variants = [p for p in protocols if p != BASELINE]
    by_key = {(r["condition"], r["load"], r["seed"], r["protocol"]): r for r in rows}

    lines = [
        "Proposed vs ARQ_HMAC, paired over identical (load, seed) traffic.",
        "Change = relative difference of the means. b/e/w = runs where Proposed is better / equal / worse.",
        f"p = two-sided Wilcoxon signed-rank test; * = significant at p < {ALPHA_SIG}.",
        "",
    ]
    for cond in conditions:
        pairs_keys = sorted({(r["load"], r["seed"]) for r in rows if r["condition"] == cond})
        lines.append("=" * 72)
        lines.append(f"{cond}  ({len(pairs_keys)} paired runs)")
        lines.append("=" * 72)
        lines.append(f"{'Metric':<22} {'Protocol':<11} {'Mean':>10} {'Change':>8} {'b/e/w':>9} {'p':>8}")
        for key, label, higher in METRICS:
            lines.append("-" * 72)
            base = [by_key[(cond, g, s, BASELINE)][key] for g, s in pairs_keys]
            lines.append(f"{label:<22} {BASELINE:<11} {mean(base):>10.3f}")
            for v in variants:
                other = [by_key[(cond, g, s, v)][key] for g, s in pairs_keys]
                diffs = [o - b for o, b in zip(other, base)]
                better = sum(1 for d in diffs if abs(d) > 1e-9 and (d > 0) == higher)
                equal = sum(1 for d in diffs if abs(d) <= 1e-9)
                worse = len(diffs) - better - equal
                mb = mean(base)
                change = f"{100 * (mean(other) - mb) / abs(mb):+.1f}%" if mb else "n/a"
                if equal == len(diffs):
                    p_txt = "  (same)"
                else:
                    p = wilcoxon(other, base, zero_method="wilcox").pvalue
                    p_txt = f"{p:.3f}" + ("*" if p < ALPHA_SIG else " ")
                lines.append(f"{'':<22} {v:<11} {mean(other):>10.3f} {change:>8} "
                             f"{f'{better}/{equal}/{worse}':>9} {p_txt:>8}")
        lines.append("")
    text = "\n".join(lines)
    with open(os.path.join(RESULTS, "statistics.txt"), "w") as f:
        f.write(text + "\n")
    print(text)


# ------------------------------------------------------------------ figures
def plot_condition(rows, condition):
    data = defaultdict(list)                       # (protocol, metric, load) -> values over seeds
    for r in rows:
        if r["condition"] == condition:
            for key, _, _ in PLOTTED:
                data[(r["protocol"], key, r["load"])].append(r[key])
    loads = sorted({r["load"] for r in rows if r["condition"] == condition})
    protocols = ordered({r["protocol"] for r in rows if r["condition"] == condition}, list(STYLE))

    fig, axes = plt.subplots(1, len(PLOTTED), figsize=(19, 4.0))
    for ax, (key, label, _) in zip(axes, PLOTTED):
        for proto in protocols:
            vals = [data[(proto, key, g)] for g in loads]
            means = [mean(v) for v in vals]
            errs = [stdev(v) if len(v) > 1 else 0 for v in vals]
            ax.errorbar(loads, means, yerr=errs, label=proto, linewidth=1.5, markersize=6,
                        capsize=3, elinewidth=1, **STYLE.get(proto, {}))
        ax.set_title(label, fontsize=10, color=TEXT, loc="left")
        ax.set_xlabel("Offered load G")
        ax.set_xticks(loads)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", ncol=len(protocols), frameon=False, fontsize=9)
    n_seeds = len({r["seed"] for r in rows if r["condition"] == condition})
    fig.suptitle(f"{condition}: mean ± std over {n_seeds} seeds", x=0.01, ha="left", fontsize=12)
    fig.tight_layout(rect=(0, 0, 1, 0.92))
    path = os.path.join(RESULTS, f"fig_{condition}.png")
    fig.savefig(path, dpi=150)
    plt.close(fig)
    return path


def plot_estimate_tracking():
    path = os.path.join(RESULTS, "estimate_trace.csv")
    if not os.path.exists(path):
        return None
    series = defaultdict(list)                     # (protocol, sensor) -> [(t, true, used)]
    condition = None
    with open(path) as f:
        for r in csv.DictReader(f):
            condition = r["condition"]
            series[(r["protocol"], r["sensor"])].append(
                (float(r["time"]), float(r["true_delay"]), float(r["delay_in_use"])))
    if not series:
        return None
    swing = lambda s: max(p[1] for p in series[(BASELINE, s)]) - min(p[1] for p in series[(BASELINE, s)])
    sensor = max({s for _, s in series}, key=swing)   # the AUV whose delay changes the most

    fig, ax = plt.subplots(figsize=(10, 4.2))
    truth = series[(BASELINE, sensor)]
    ax.plot([p[0] for p in truth], [p[1] * 1000 for p in truth], color=TEXT_2, linewidth=3, alpha=0.35,
            label="True delay")
    for proto in [p for p in TRACKED if (p, sensor) in series]:
        pts = series[(proto, sensor)]
        st = STYLE.get(proto, {})
        ax.plot([p[0] for p in pts], [p[2] * 1000 for p in pts], color=st.get("color"),
                linestyle=st.get("linestyle"), linewidth=1.5, label=f"{proto} (delay in use)")
    ax.set_xlabel("Simulation time (s)")
    ax.set_ylabel("One-way delay to master (ms)")
    ax.set_title(f"{condition}, sensor {sensor}: delay estimate vs true propagation delay",
                 loc="left", fontsize=11)
    ax.legend(frameon=False, loc="upper left", bbox_to_anchor=(1.01, 1))
    fig.tight_layout()
    out = os.path.join(RESULTS, "fig_estimate_tracking.png")
    fig.savefig(out, dpi=150)
    plt.close(fig)
    return out


def main():
    rows = read_runs()
    if not rows:
        raise SystemExit("No results/runs_*.csv found - run run_comparison.groovy first.")
    statistics(rows)
    for c in dict.fromkeys(r["condition"] for r in rows):
        print("wrote", plot_condition(rows, c))
    p = plot_estimate_tracking()
    if p:
        print("wrote", p)
    print("wrote", os.path.join(RESULTS, "statistics.txt"))


if __name__ == "__main__":
    main()
