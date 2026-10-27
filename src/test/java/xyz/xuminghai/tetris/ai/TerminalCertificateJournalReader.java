/*
 * Copyright (C) 2024-2026 xuMingHai
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded, strict consumption of an existing journal; never starts geometry/order search.
 * Structural completion and independently replayed claims do not authenticate the producer,
 * search coverage, ranks/guards or a registered diagnostic. Missing finish remains INCOMPLETE.
 */
final class TerminalCertificateJournalReader {
    // At most 128 attempts, start and finish; each embedded steps-v1 is at most 1 MiB.
    // A second MiB leaves ample room for the bounded 16 facts and JSON envelope.
    static final int MAX_LINE_BYTES = 2 * TerminalCertificateRecords.MAX_BYTES;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private TerminalCertificateJournalReader() { }

    enum State { INCOMPLETE, ERROR, COMPLETE }
    record Summary(State state, int attempts, int enumerations, int solverReplays, int checkerReplays) { }

    /** Malformed or contradictory complete records throw; a torn final line is never consumed. */
    static Summary read(Path path) throws IOException {
        var state = new ReaderState();
        try (var input = new BufferedInputStream(Files.newInputStream(path))) {
            for (int count = 0; ; count++) {
                var line = new ByteArrayOutputStream();
                int next;
                while ((next = input.read()) != -1 && next != '\n') {
                    require(line.size() < MAX_LINE_BYTES, "journal line too large");
                    line.write(next);
                }
                if (next == -1) {
                    // Bytes after a terminal event are invalid, even if their line is torn.
                    require(line.size() == 0 || !state.terminal, "bytes after terminal event");
                    return state.summary();
                }
                require(count < 130 && !state.terminal, "events after terminal or event limit");
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(line.toByteArray())).toString();
                } catch (CharacterCodingException failure) {
                    throw new IllegalArgumentException("invalid UTF-8", failure);
                }
                state.consume(JSON.readTree(text));
            }
        }
    }

    private static final class ReaderState {
        private TerminalCertificateGeometry.Domain domain;
        private TerminalCertificateSession.Limits limits;
        private int attempts;
        private int enumerations;
        private int solverReplays;
        private int checkerReplays;
        private int certificate;
        private boolean enumerationLimit;
        private boolean terminal;
        private State state = State.INCOMPLETE;

        private Summary summary() {
            return new Summary(state, attempts, enumerations, solverReplays, checkerReplays);
        }

        private void consume(JsonNode root) {
            fields(root, "schema", "event", "data", "provenance_authenticated", "eligible_for_runtime_validation");
            require(string(root.get("schema")).equals("terminal-certificate-session-v1"), "session schema");
            require(!bool(root.get("provenance_authenticated"))
                    && !bool(root.get("eligible_for_runtime_validation")), "unauthorized permission claim");
            var data = root.get("data");
            switch (string(root.get("event"))) {
                case "start" -> start(data);
                case "attempt" -> attempt(data);
                case "finish" -> finish(data);
                case "error" -> {
                    fields(data, "state", "exception_class");
                    require(string(data.get("state")).equals("ERROR"), "error state");
                    require(!string(data.get("exception_class")).isBlank(), "exception class");
                    state = State.ERROR;
                    terminal = true;
                }
                default -> throw new IllegalArgumentException("unknown event");
            }
        }

        private void start(JsonNode data) {
            require(domain == null, "duplicate start");
            fields(data, "state", "domain", "limits");
            require(string(data.get("state")).equals("INCOMPLETE"), "start state");
            var d = data.get("domain");
            fields(d, "rows", "cols", "required", "allowed");
            domain = new TerminalCertificateGeometry.Domain(integer(d.get("rows")), integer(d.get("cols")),
                    bits(d.get("required"), 20), bits(d.get("allowed"), 20));
            var l = data.get("limits");
            fields(l, "nodes", "decompositions", "enumerations", "steps");
            limits = new TerminalCertificateSession.Limits(integer(l.get("nodes")), integer(l.get("decompositions")),
                    integer(l.get("enumerations")), integer(l.get("steps")));
        }

        private void attempt(JsonNode data) {
            require(domain != null && !enumerationLimit && certificate == 0, "attempt outside active search");
            fields(data, "decomposition", "order_stop", "enumerations", "solver_replays", "steps", "facts", "checked");
            require(integer(data.get("decomposition")) == ++attempts && attempts <= limits.decompositions(),
                    "attempt sequence or limit");
            int enums = integer(data.get("enumerations"));
            int replays = integer(data.get("solver_replays"));
            require(enums > enumerations && enums <= limits.enumerations()
                    && replays >= solverReplays && replays - solverReplays <= enums - enumerations,
                    "nonmonotonic or impossible counters");
            enumerations = enums;
            solverReplays = replays;
            var stop = TerminalCertificateOrder.Stop.valueOf(string(data.get("order_stop")));
            enumerationLimit = enums == limits.enumerations();
            require((stop == TerminalCertificateOrder.Stop.ENUMERATION_LIMIT) == enumerationLimit, "order limit stop");
            var facts = data.get("facts");
            require(facts.isArray(), "facts array");
            if (data.get("steps").isNull()) {
                require(stop != TerminalCertificateOrder.Stop.ORDER_FOUND && data.get("checked").isNull()
                        && facts.isEmpty(), "incomplete order claims");
                return;
            }
            require(stop != TerminalCertificateOrder.Stop.EXHAUSTED, "complete order stop");
            var stepText = data.get("steps").toString();
            require(stepText.getBytes(StandardCharsets.UTF_8).length + 1 <= TerminalCertificateRecords.MAX_BYTES,
                    "embedded steps too large");
            var steps = TerminalCertificateRecords.parse(stepText);
            require(steps.size() <= limits.steps() && facts.size() == steps.size(), "facts/step count");
            var checked = TerminalCertificateChecker.check(domain, steps);
            var c = data.get("checked");
            fields(c, "replays", "final_board", "terminal");
            require(integer(c.get("replays")) == checked.replays()
                    && bits(c.get("final_board"), 50).equals(checked.finalBoard()), "checker mismatch");
            require(c.get("terminal").equals(JSON.valueToTree(TerminalCertificateJournal.terminal(checked.terminal()))),
                    "checker terminal mismatch");
            int bag = 0;
            for (int i = 0; i < steps.size(); i++) {
                var fact = facts.get(i);
                fields(fact, "index", "bag_before", "bag_after", "survival_rank", "ordinary_allowed",
                        "original_guard", "resulting_facts");
                require(integer(fact.get("index")) == i && integer(fact.get("bag_before")) == bag, "fact index/bag");
                bag |= 1 << steps.get(i).type().ordinal();
                if (bag == 127) bag = 0;
                require(integer(fact.get("bag_after")) == bag, "bag after");
                int rank = integer(fact.get("survival_rank"));
                require(rank > 0, "rank");
                var g = fact.get("original_guard");
                fields(g, "outside_top_five", "danger", "budget_allowed", "holes_delta",
                        "aggregate_height_delta", "bumpiness_delta", "risk", "profile");
                var guard = new GoalWindowCandidate.Guard(bool(g.get("outside_top_five")), bool(g.get("danger")),
                        bool(g.get("budget_allowed")), integer(g.get("holes_delta")),
                        integer(g.get("aggregate_height_delta")), integer(g.get("bumpiness_delta")),
                        string(g.get("risk")), string(g.get("profile")));
                var risk = ObjectiveRiskController.RiskLevel.valueOf(guard.risk());
                require(ObjectiveRiskProfile.valueOf(guard.profile()) == ObjectiveRiskController.adaptiveProfile(risk)
                        && guard.danger() == (risk == ObjectiveRiskController.RiskLevel.DANGER), "risk/profile claim");
                require(guard.outsideTopFive() == (rank > 5)
                        && bool(fact.get("ordinary_allowed")) == (rank == 1 || guard.ordinaryAllowed()), "guard claim");
                var board = new boolean[20][10];
                for (int bit = 0; bit < 200; bit++) board[bit / 10][bit % 10] = steps.get(i).postBoard().testBit(bit);
                require(fact.get("resulting_facts").equals(JSON.valueToTree(
                        TerminalCertificateJournal.terminal(GoalWindowCandidate.terminal(board)))), "step terminal mismatch");
            }
            checkerReplays += checked.replays();
            require(checkerReplays <= solverReplays, "checker exceeds solver replay");
            if (domain.equals(TerminalCertificateGeometry.Domain.heart()) && checked.terminal().accepted()) {
                certificate = attempts;
            }
        }

        private void finish(JsonNode data) {
            require(domain != null, "finish without start");
            fields(data, "state", "search_status", "geometry", "enumerations", "solver_replays",
                    "checker_replays", "enumeration_limit_reached", "certificate_decomposition");
            require(string(data.get("state")).equals("SESSION_COMPLETE"), "finish state");
            require(integer(data.get("enumerations")) == enumerations
                    && integer(data.get("solver_replays")) == solverReplays
                    && integer(data.get("checker_replays")) == checkerReplays
                    && bool(data.get("enumeration_limit_reached")) == enumerationLimit
                    && integer(data.get("certificate_decomposition")) == certificate, "finish counters");
            require(string(data.get("search_status")).equals(certificate == 0 ? "BOUNDED_MISS" : "Q_FOUND"),
                    "search status");
            var geometry = data.get("geometry");
            fields(geometry, "nodes", "decompositions", "stop");
            int nodes = integer(geometry.get("nodes"));
            require(nodes >= Math.max(1, attempts) && nodes <= limits.nodes()
                    && integer(geometry.get("decompositions")) == attempts, "geometry counters");
            var stop = TerminalCertificateGeometry.Stop.valueOf(string(geometry.get("stop")));
            // Mirror the frozen priority: decomposition cap, consumer stop, node cap, exhaustion.
            var expected = attempts == limits.decompositions() ? TerminalCertificateGeometry.Stop.DECOMPOSITION_LIMIT
                    : certificate != 0 || enumerationLimit ? TerminalCertificateGeometry.Stop.CONSUMER_STOP
                    : nodes == limits.nodes() ? TerminalCertificateGeometry.Stop.NODE_LIMIT
                    : TerminalCertificateGeometry.Stop.EXHAUSTED;
            require(stop == expected, "geometry stop");
            state = State.COMPLETE;
            terminal = true;
        }
    }

    private static BigInteger bits(JsonNode node, int digits) {
        var value = string(node);
        require(value.matches("0|[1-9a-f][0-9a-f]{0," + (digits - 1) + "}"), "noncanonical bits");
        return new BigInteger(value, 16);
    }

    private static int integer(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToInt(), "integer required");
        return node.intValue();
    }

    private static boolean bool(JsonNode node) {
        require(node != null && node.isBoolean(), "boolean required");
        return node.booleanValue();
    }

    private static String string(JsonNode node) {
        require(node != null && node.isString(), "string required");
        return node.asString();
    }

    private static void fields(JsonNode node, String... expected) {
        require(node != null && node.isObject(), "object required");
        var actual = new HashSet<String>();
        node.propertyNames().forEach(actual::add);
        require(actual.equals(Set.of(expected)), "missing or unknown fields");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
