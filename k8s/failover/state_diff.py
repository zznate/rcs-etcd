#!/usr/bin/env python3
"""Compare two `_cluster/state` JSON snapshots and report any
significant differences across a manager kill / re-election.

Significant = differences in declarative state (indices, mappings,
settings, templates, pipelines, persistent + transient settings).
Insignificant (ignored) = anything that legitimately changes during
a manager re-election: node identities, routing assignments, the
monotonic cluster-state version, master_node, election term.

Exits 0 on a clean diff (state survived bit-identical modulo the
ignored fields), 1 otherwise. Prints the first 25 differences for
human triage; the count line shows how many were truncated.
"""

import argparse
import json
import sys


# Paths to drop from both snapshots before diffing. Tuple form;
# "*" matches any single segment. Match is prefix-anchored on the
# normalised path so e.g. ("nodes",) drops everything under nodes.
IGNORED = [
    # Election + state identity — both change every re-election.
    ("master_node",),
    ("cluster_manager_node",),
    ("state_uuid",),
    ("version",),
    ("metadata", "cluster_coordination"),
    # Topology / routing — node IDs change when pods restart;
    # allocations and primary terms bump as shards relocate.
    ("nodes",),
    ("routing_table",),
    ("routing_nodes",),
    ("metadata", "indices", "*", "in_sync_allocations"),
    ("metadata", "indices", "*", "primary_terms"),
    ("metadata", "indices", "*", "primary_terms_map"),
    # Per-index opaque version counters that bump even without
    # user-visible changes (mapping/settings/aliases internal v#s).
    ("metadata", "indices", "*", "version"),
    ("metadata", "indices", "*", "mapping_version"),
    ("metadata", "indices", "*", "settings_version"),
    ("metadata", "indices", "*", "aliases_version"),
    ("metadata", "indices", "*", "settings", "index", "version"),
    ("metadata", "indices", "*", "settings", "index", "provided_name"),
    # In-flight snapshot bookkeeping — orthogonal to declarative state.
    ("snapshots",),
    ("snapshot_deletions",),
]


def _path_matches(actual_path, pattern):
    """Prefix match with `*` as a single-segment wildcard."""
    if len(actual_path) < len(pattern):
        return False
    for a, p in zip(actual_path, pattern):
        if p == "*":
            continue
        if a != p:
            return False
    return True


def strip(obj, ignored, path=()):
    """Recursively remove fields whose path matches any ignored pattern."""
    if isinstance(obj, dict):
        result = {}
        for k, v in obj.items():
            new_path = path + (k,)
            if any(_path_matches(new_path, ig) for ig in ignored):
                continue
            result[k] = strip(v, ignored, new_path)
        return result
    if isinstance(obj, list):
        return [strip(x, ignored, path) for x in obj]
    return obj


def diff(a, b, path=()):
    """Return a list of (path, before, after) tuples for every mismatch."""
    if type(a) is not type(b):
        return [(path, a, b)]
    if isinstance(a, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            sub = path + (k,)
            if k not in a:
                out.append((sub, "<missing>", b[k]))
            elif k not in b:
                out.append((sub, a[k], "<missing>"))
            else:
                out.extend(diff(a[k], b[k], sub))
        return out
    if isinstance(a, list):
        return [] if a == b else [(path, a, b)]
    return [] if a == b else [(path, a, b)]


def _short(value):
    encoded = json.dumps(value, default=str)
    return encoded if len(encoded) <= 80 else encoded[:77] + "..."


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("expected", help="Pre-kill cluster-state snapshot (JSON)")
    parser.add_argument("actual", help="Post-recovery cluster-state snapshot (JSON)")
    args = parser.parse_args()

    with open(args.expected) as f:
        expected = json.load(f)
    with open(args.actual) as f:
        actual = json.load(f)

    expected_stripped = strip(expected, IGNORED)
    actual_stripped = strip(actual, IGNORED)

    diffs = diff(expected_stripped, actual_stripped)
    if not diffs:
        print("STATE DIFF: clean (no significant changes across failover)")
        return 0

    print(f"STATE DIFF: {len(diffs)} mismatch(es)")
    for p, before, after in diffs[:25]:
        path_str = ".".join(str(s) for s in p) or "<root>"
        print(f"  {path_str}")
        print(f"    BEFORE: {_short(before)}")
        print(f"    AFTER:  {_short(after)}")
    if len(diffs) > 25:
        print(f"  ... and {len(diffs) - 25} more")
    return 1


if __name__ == "__main__":
    sys.exit(main())
