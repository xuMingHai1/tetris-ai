/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Synchronous test-only search/check chain. It is not an authenticated diagnostic entry point.
 * The explicit test CLI invokes it; external provenance and deadline supervision remain required.
 */
final class TerminalCertificateSession {
    private TerminalCertificateSession() { }

    /** Only fixtures may lower these maxima; no caller may enlarge the registered search. */
    record Limits(int nodes, int decompositions, int enumerations, int steps) {
        Limits {
            if (nodes < 1 || nodes > TerminalCertificateGeometry.NODE_LIMIT
                    || decompositions < 1 || decompositions > TerminalCertificateGeometry.DECOMPOSITION_LIMIT
                    || enumerations < 1 || enumerations > TerminalCertificateOrder.ENUMERATION_LIMIT
                    || steps < 1 || steps > TerminalCertificateGeometry.TILE_LIMIT) {
                throw new IllegalArgumentException("limits outside registered maxima");
            }
        }

        static Limits registered() {
            return new Limits(200_000, 128, 2_000, 16);
        }
    }

    enum Status { Q_FOUND, BOUNDED_MISS }

    /** Delivered before the geometry consumer returns, including complete orders that fail Q. */
    record Attempt(int decomposition, TerminalCertificateOrder.Result order,
                   Optional<TerminalCertificateChecker.Checked> checked) { }

    record Result(Status status, TerminalCertificateGeometry.Result geometry,
                  int enumerations, int solverReplays, int checkerReplays,
                  boolean enumerationLimitReached, Optional<Attempt> certificate) { }

    static Result search(TerminalCertificateGeometry.Domain domain, Limits limits,
                         Consumer<Attempt> observer) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(observer, "observer");
        // This one instance owns ALL covers. Recording facts must not enumerate again.
        var order = new TerminalCertificateOrder(limits.enumerations());
        var state = new State();
        var geometry = TerminalCertificateGeometry.enumerate(domain, limits.nodes(),
                limits.decompositions(), limits.steps(), cover -> {
                    var searched = order.search(domain, cover);
                    state.enumerations = searched.enumerations();
                    state.solverReplays = searched.replays();
                    state.limit = searched.stop() == TerminalCertificateOrder.Stop.ENUMERATION_LIMIT;
                    Optional<TerminalCertificateChecker.Checked> checked = Optional.empty();
                    if (searched.order().isPresent()) {
                        // Even the last permitted enumeration may finish a real certificate.
                        // Do not let a simultaneous count stop bypass independent replay or Q.
                        var replayed = TerminalCertificateChecker.check(domain, searched.order().orElseThrow());
                        state.checkerReplays += replayed.replays();
                        checked = Optional.of(replayed);
                    }
                    var attempt = new Attempt(++state.decompositions, searched, checked);
                    // Observation failures propagate; they must never turn into a bounded miss.
                    observer.accept(attempt);
                    if (domain.equals(TerminalCertificateGeometry.Domain.heart())
                            && checked.isPresent() && checked.orElseThrow().terminal().accepted()) {
                        state.certificate = attempt;
                    }
                    return state.certificate != null || state.limit;
                });
        return new Result(state.certificate == null ? Status.BOUNDED_MISS : Status.Q_FOUND,
                geometry, state.enumerations, state.solverReplays, state.checkerReplays,
                state.limit, Optional.ofNullable(state.certificate));
    }

    private static final class State {
        private int decompositions;
        private int enumerations;
        private int solverReplays;
        private int checkerReplays;
        private boolean limit;
        private Attempt certificate;
    }
}
