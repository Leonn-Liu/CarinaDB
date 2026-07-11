#!/usr/bin/env python3
"""
hnswlib reference benchmark for CarinaDB comparison.

Matches HnswScalingBenchmark parameters exactly:
  M=16, efConstruction=200, efSearch=200, K=10, dim=128, n_queries=300
  Distributions: uniform (worst-case) and manifold (intrinsic dim=16, realistic embeddings)

Usage:
    pip install hnswlib numpy
    python3 benchmarks/run_hnswlib.py        # from project root
    # Results → benchmarks/data/hnswlib_results.json
"""

import os
import json
import time
import numpy as np

try:
    import hnswlib
except ImportError:
    print("ERROR: pip install hnswlib numpy")
    raise

SEED = 42
DIM = 128
K = 10
N_QUERIES = 300
N_WARMUP = 50
SIZES = [20_000, 50_000, 100_000, 200_000]
M = 16
EF_CONSTRUCTION = 200
EF_SEARCH = 200  # CarinaDB currently uses efConstruction as efSearch


def generate_uniform(n, dim=DIM, seed=SEED):
    """Uniform in [-1, 1] — matches Java: random.nextFloat() * 2 - 1."""
    rng = np.random.default_rng(seed)
    return (rng.random((n + N_QUERIES, dim)).astype(np.float32) * 2 - 1)


def generate_manifold(n, dim=DIM, intrinsic_dim=16, seed=SEED):
    """Matches Java HnswScalingBenchmark manifold generation exactly:
      manifoldBasis[DIM][INTRINSIC_DIM] ~ N(0,1), NOT normalized.
      v[i] = sum_j(basis[i][j] * z[j]) + 0.01 * N(0,1)
    """
    rng = np.random.default_rng(seed)
    total = n + N_QUERIES
    basis = rng.standard_normal((dim, intrinsic_dim))
    latents = rng.standard_normal((total, intrinsic_dim))
    # macOS Accelerate BLAS raises benign RuntimeWarning during matmul; suppress it.
    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        signal = (latents @ basis.T).astype(np.float32)
    noise = rng.standard_normal((total, dim)).astype(np.float32) * 0.01
    result = signal + noise
    assert not np.any(np.isnan(result)), "NaN in manifold data"
    return result


def exact_knn(data, queries, k):
    results = []
    for q in queries:
        dists = np.sum((data - q) ** 2, axis=1)
        results.append(np.argsort(dists)[:k])
    return results


def recall_at_k(predicted, ground_truth, k):
    hits = sum(len(set(p[:k]) & set(g[:k])) for p, g in zip(predicted, ground_truth))
    return hits / (len(predicted) * k)


def run_one_size(data_all, n):
    data = data_all[:n]
    queries = data_all[n:n + N_QUERIES]

    # Build index
    t0 = time.perf_counter()
    idx = hnswlib.Index(space='l2', dim=DIM)
    idx.init_index(max_elements=n, ef_construction=EF_CONSTRUCTION, M=M)
    idx.add_items(data, list(range(n)))
    build_s = time.perf_counter() - t0

    idx.set_ef(EF_SEARCH)

    # Ground truth (brute force)
    gt = exact_knn(data, queries, K)

    # Warmup
    for q in queries[:N_WARMUP]:
        idx.knn_query(q.reshape(1, -1), k=K)

    # Timed search
    t0 = time.perf_counter()
    preds = [idx.knn_query(q.reshape(1, -1), k=K)[0][0] for q in queries]
    elapsed = time.perf_counter() - t0

    qps = N_QUERIES / elapsed
    us_per_q = elapsed / N_QUERIES * 1e6
    recall = recall_at_k(preds, gt, K)

    return {
        "n": n,
        "build_s": round(build_s, 1),
        "qps": round(qps, 0),
        "us_per_q": round(us_per_q, 1),
        "recall_at_10": round(recall, 4),
    }


def run_distribution(name, gen_fn):
    print(f"\n{'='*50}")
    print(f"Distribution: {name}")
    print(f"  M={M}  efConstruction={EF_CONSTRUCTION}  efSearch={EF_SEARCH}")
    print(f"  K={K}  queries={N_QUERIES}  dim={DIM}")
    print(f"{'='*50}")

    # Pre-generate max size; slice per N to keep data consistent across sizes
    data_all = gen_fn(max(SIZES))
    results = []

    for n in SIZES:
        r = run_one_size(data_all, n)
        print(f"  N={n:>7,}  build={r['build_s']:>5.1f}s  "
              f"QPS={r['qps']:>7,.0f}  us/q={r['us_per_q']:>6.1f}  "
              f"recall@10={r['recall_at_10']:.4f}")
        results.append(r)

    return results


def main():
    os.makedirs("benchmarks/data", exist_ok=True)

    results = {
        "meta": {
            "tool": "hnswlib",
            "M": M,
            "ef_construction": EF_CONSTRUCTION,
            "ef_search": EF_SEARCH,
            "K": K,
            "dim": DIM,
            "n_queries": N_QUERIES,
            "n_warmup": N_WARMUP,
        },
        "uniform": run_distribution("Uniform 128-dim (worst case, high-dim random)",
                                    lambda n: generate_uniform(n)),
        "manifold": run_distribution("Manifold intrinsic-dim=16 → 128-dim (realistic embeddings)",
                                     lambda n: generate_manifold(n)),
    }

    out = "benchmarks/data/hnswlib_results.json"
    with open(out, "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nSaved → {out}")


if __name__ == "__main__":
    main()
