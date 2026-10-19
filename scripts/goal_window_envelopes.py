"""Assemble fixed GoalWindow development/calibration inputs; no provenance attestation."""
import copy

import analyze_heart_paired as paired


def assemble_envelopes(records, metadata, protocol_bytes):
    """Return two frozen #105 envelopes from all 160 observed measured games.

    Each record is {"arm": "baseline" | "candidate", "game": <#105 game>}.
    Records must retain observed seed/parity order. Metadata is one shared #105
    header, without population or arm games; warmup references describe the same
    session in both outputs. Claimed evidence kind is preserved, never upgraded.
    The caller must separately audit raw completeness and authenticate provenance.
    """
    try:
        return _assemble(records, metadata, protocol_bytes)
    except (KeyError, TypeError, AttributeError, OverflowError) as failure:
        raise ValueError("missing or malformed GoalWindow envelope input") from failure


def _assemble(records, metadata, protocol_bytes):
    header_keys = {"schema", "protocol_sha256", "evidence_kind", "timing_scope",
                   "input_boundary", "reset_strategy_per_game", "environment", "arms"}
    paired.require(isinstance(metadata, dict) and set(metadata) == header_keys,
                   "expected shared paired metadata without population/games")
    paired.require(isinstance(metadata["arms"], dict)
                   and set(metadata["arms"]) == {"baseline", "candidate"}, "expected both arm headers")
    arm_keys = {"source_sha", "config", "config_sha256", "production_base_sha", "warmup"}
    for arm in metadata["arms"].values():
        paired.require(isinstance(arm, dict) and set(arm) == arm_keys,
                       "expected explicit arm metadata without games")
    paired.require(metadata["arms"]["baseline"]["source_sha"]
                   == metadata["arms"]["candidate"]["source_sha"], "mixed source checkouts")
    paired.require(isinstance(records, list) and len(records) == 160,
                   "expected all 80 measured seed pairs")
    envelopes = {}
    for population in ("development", "calibration"):
        envelope = copy.deepcopy(metadata)
        envelope["population"] = population
        for arm in envelope["arms"].values():
            arm["games"] = []
        envelopes[population] = envelope
    for index, record in enumerate(records):
        seed = 20000 + index // 2
        arm = "baseline" if index % 2 == seed % 2 else "candidate"
        paired.require(isinstance(record, dict) and set(record) == {"arm", "game"}
                       and record["arm"] == arm, "missing or reordered measured arm")
        game = record["game"]
        paired.require(isinstance(game, dict) and paired.integer(game["seed"]) == seed,
                       "missing, duplicated, reordered or unregistered seed")
        paired.validate_game(game, 1000, index % 2)
        paired.require(all(game[key] == 0 for key in
                           ("primary_failures", "fallback_decisions", "illegal_plans")),
                       "failed game cannot become complete paired evidence")
        population = "development" if seed < 20020 else "calibration"
        envelopes[population]["arms"][arm]["games"].append(copy.deepcopy(game))
    # Use the unchanged consumer for protocol, metadata and full per-group checks.
    # A valid diagnostic group can fail adoption gates; that is not invalid input.
    for envelope in envelopes.values():
        paired.analyze(envelope, protocol_bytes)
    return envelopes
