/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;

/** Explicit offline file replay. Never enumerates covers or authenticates a diagnostic run. */
public final class TerminalCertificateReplay {
    private TerminalCertificateReplay() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--check")) {
            throw new IllegalArgumentException("usage: --check <certificate-steps.json>");
        }
        var checked = TerminalCertificateChecker.check(TerminalCertificateGeometry.Domain.heart(),
                TerminalCertificateRecords.read(Path.of(args[1])));
        if (!checked.terminal().accepted()) throw new IllegalArgumentException("replayed terminal fails HEART Q");
        System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                "schema", "terminal-certificate-replay-v1", "status", "REPLAYED_Q",
                "checker_replays", checked.replays(), "terminal", checked.terminal(),
                "final_board", checked.finalBoard().toString(16), "diagnostic_started", false,
                "provenance_authenticated", false, "eligible_for_runtime_validation", false)));
    }
}
