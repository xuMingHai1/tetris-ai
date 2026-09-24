/*
 * Copyright (C) 2024-2026 xuMingHai
 *
 * This file is part of Tetris and is distributed under the GNU GPL v3.
 */
package xyz.xuminghai.tetris.ai.benchmark;

import xyz.xuminghai.tetris.ai.BuildShapeActionPlanningAgent;
import xyz.xuminghai.tetris.ai.RecoveryRobustnessBenchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Observes temporal persistence and recovery of the already-frozen recovery-reserve warning.
 *
 * <p>This application does not tune recovery headroom or hole thresholds and does not define a
 * runtime persistence threshold. Every successful production BUILD_SHAPE placement is evaluated
 * with {@link RecoveryReserveValidationApplication}'s frozen warning contract. Consecutive warning
 * placements are then grouped into maximal episodes so transient recoveries can be distinguished
 * from warnings that persist to game termination.</p>
 *
 * <p>Games that reach the configured horizon are healthy controls. Games that terminate earlier
 * are production failures. An episode ending on the final successful placement is terminal for a
 * failed game and horizon-censored for a healthy game; neither case is silently counted as a
 * recovered episode.</p>
 */
public final class RecoveryReservePersistenceValidationApplication {

    static final String GAMES_ENV = "TETRIS_BENCHMARK_GAMES";
    static final String MAX_PIECES_ENV = "TETRIS_BENCHMARK_MAX_PIECES";
    static final String SEED_ENV = "TETRIS_BENCHMARK_SEED";

    private static final int DEFAULT_GAMES = 30;
    private static final int DEFAULT_MAX_PIECES = 1000;
    private static final long DEFAULT_SEED = 4000L;

    private RecoveryReservePersistenceValidationApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        List<WarningSample> warnings = new ArrayList<>();
        List<Episode> episodes = new ArrayList<>();
        List<GameObservation> games = new ArrayList<>();

        collect(configuration, warnings, episodes, games);

        System.out.println(
                "recovery_reserve_persistence_warning,source,seed,position,distance_to_failure,"
                        + "warning_reason,preview_piece,preview_reachable_outcomes,"
                        + "preview_recoverable,recovery_headroom,recovery_aggregate_height,"
                        + "recovery_holes,recovery_bumpiness");
        for (WarningSample warning : warnings) {
            System.out.println(formatWarning(warning));
        }

        System.out.println(
                "recovery_reserve_persistence_episode,source,seed,start_position,end_position,"
                        + "length,recovered,terminal_or_censored,start_distance_to_failure,"
                        + "end_distance_to_failure");
        for (Episode episode : episodes) {
            System.out.println(formatEpisode(episode));
        }

        System.out.println(
                "recovery_reserve_persistence_game,source,seed,final_position,warning_samples,"
                        + "episodes,max_episode_length,terminal_or_censored_episode,"
                        + "first_warning_position,first_warning_lead");
        for (GameObservation game : games) {
            System.out.println(formatGame(game));
        }

        printProtocol(configuration);
        printSummary(warnings, episodes, games);
    }

    private static void collect(
            Configuration configuration,
            List<WarningSample> warnings,
            List<Episode> episodes,
            List<GameObservation> games) {
        HeadlessGameRunner runner = new HeadlessGameRunner();

        for (int game = 0; game < configuration.games(); game++) {
            long seed = configuration.seed() + game;
            List<HeadlessGameRunner.TurnObservation> turns = new ArrayList<>();
            GameBenchmarkResult result = runner.runPlanningObserved(
                    seed,
                    configuration.maxPieces(),
                    new BuildShapeActionPlanningAgent(),
                    null,
                    turns::add);

            Source source = result.reachedPieceLimit()
                    ? Source.HEALTHY_RUNTIME
                    : Source.FAILED_RUNTIME;
            int finalPosition = turns.isEmpty() ? 0 : turns.getLast().decision();
            List<Integer> warningPositions = new ArrayList<>();

            for (HeadlessGameRunner.TurnObservation turn : turns) {
                RecoveryRobustnessBenchmark.Probe probe =
                        RecoveryRobustnessBenchmark.probe(
                                turn.selected().resultingBoard(),
                                turn.snapshot().nextType().orElseThrow());
                if (!warning(probe)) {
                    continue;
                }

                int distanceToFailure = source == Source.FAILED_RUNTIME
                        ? finalPosition - turn.decision()
                        : -1;
                warningPositions.add(turn.decision());
                warnings.add(new WarningSample(
                        source,
                        seed,
                        turn.decision(),
                        distanceToFailure,
                        warningReason(probe),
                        probe));
            }

            List<Episode> gameEpisodes =
                    buildEpisodes(source, seed, warningPositions, finalPosition);
            episodes.addAll(gameEpisodes);
            games.add(GameObservation.from(
                    source,
                    seed,
                    finalPosition,
                    warningPositions,
                    gameEpisodes));
        }
    }

    static boolean warning(RecoveryRobustnessBenchmark.Probe probe) {
        return RecoveryReserveValidationApplication.warning(probe);
    }

    static String warningReason(RecoveryRobustnessBenchmark.Probe probe) {
        return RecoveryReserveValidationApplication.warningReason(probe);
    }

    static List<Episode> buildEpisodes(
            Source source,
            long seed,
            List<Integer> warningPositions,
            int finalPosition) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(warningPositions, "warningPositions");
        if (finalPosition < 0) {
            throw new IllegalArgumentException("finalPosition must not be negative");
        }
        if (warningPositions.isEmpty()) {
            return List.of();
        }

        List<Episode> episodes = new ArrayList<>();
        int start = requireValidPosition(warningPositions.getFirst(), finalPosition);
        int previous = start;

        for (int index = 1; index < warningPositions.size(); index++) {
            int current = requireValidPosition(warningPositions.get(index), finalPosition);
            if (current <= previous) {
                throw new IllegalArgumentException(
                        "warning positions must be strictly increasing");
            }
            if (current == previous + 1) {
                previous = current;
                continue;
            }

            episodes.add(createEpisode(source, seed, start, previous, finalPosition));
            start = current;
            previous = current;
        }

        episodes.add(createEpisode(source, seed, start, previous, finalPosition));
        return List.copyOf(episodes);
    }

    private static int requireValidPosition(int position, int finalPosition) {
        if (position <= 0 || position > finalPosition) {
            throw new IllegalArgumentException(
                    "warning position must be within the observed game");
        }
        return position;
    }

    private static Episode createEpisode(
            Source source,
            long seed,
            int start,
            int end,
            int finalPosition) {
        boolean terminalOrCensored = end == finalPosition;
        boolean recovered = !terminalOrCensored;
        int startDistance = source == Source.FAILED_RUNTIME
                ? finalPosition - start
                : -1;
        int endDistance = source == Source.FAILED_RUNTIME
                ? finalPosition - end
                : -1;
        return new Episode(
                source,
                seed,
                start,
                end,
                end - start + 1,
                recovered,
                terminalOrCensored,
                startDistance,
                endDistance);
    }

    static String formatWarning(WarningSample warning) {
        Objects.requireNonNull(warning, "warning");
        RecoveryRobustnessBenchmark.Probe probe = warning.probe();
        return String.format(
                Locale.ROOT,
                "recovery_reserve_persistence_warning,%s,%d,%d,%d,%s,%s,%d,%s,%d,%d,%d,%d",
                warning.source().configValue,
                warning.seed(),
                warning.position(),
                warning.distanceToFailure(),
                warning.reason(),
                probe.previewType().name(),
                probe.previewReachableOutcomes(),
                probe.previewRecoverable(),
                probe.recoveryHeadroom(),
                probe.recoveryAggregateHeight(),
                probe.recoveryHoles(),
                probe.recoveryBumpiness());
    }

    static String formatEpisode(Episode episode) {
        Objects.requireNonNull(episode, "episode");
        return String.format(
                Locale.ROOT,
                "recovery_reserve_persistence_episode,%s,%d,%d,%d,%d,%s,%s,%d,%d",
                episode.source().configValue,
                episode.seed(),
                episode.startPosition(),
                episode.endPosition(),
                episode.length(),
                episode.recovered(),
                episode.terminalOrCensored(),
                episode.startDistanceToFailure(),
                episode.endDistanceToFailure());
    }

    static String formatGame(GameObservation game) {
        Objects.requireNonNull(game, "game");
        return String.format(
                Locale.ROOT,
                "recovery_reserve_persistence_game,%s,%d,%d,%d,%d,%d,%s,%d,%d",
                game.source().configValue,
                game.seed(),
                game.finalPosition(),
                game.warningSamples(),
                game.episodes(),
                game.maxEpisodeLength(),
                game.terminalOrCensoredEpisode(),
                game.firstWarningPosition(),
                game.firstWarningLead());
    }

    private static void printProtocol(Configuration configuration) {
        System.out.printf(
                Locale.ROOT,
                "# recovery_reserve_persistence_protocol seed_start=%d games=%d max_pieces=%d "
                        + "sampling=every_successful_placement recovery_headroom_cutoff=%d "
                        + "recovery_holes_cutoff=%d%n",
                configuration.seed(),
                configuration.games(),
                configuration.maxPieces(),
                RecoveryReserveValidationApplication.RECOVERY_HEADROOM_CUTOFF,
                RecoveryReserveValidationApplication.RECOVERY_HOLES_CUTOFF);
    }

    private static void printSummary(
            List<WarningSample> warnings,
            List<Episode> episodes,
            List<GameObservation> games) {
        List<GameObservation> healthyGames = games.stream()
                .filter(game -> game.source() == Source.HEALTHY_RUNTIME)
                .toList();
        List<GameObservation> failedGames = games.stream()
                .filter(game -> game.source() == Source.FAILED_RUNTIME)
                .toList();
        List<Episode> healthyEpisodes = episodes.stream()
                .filter(episode -> episode.source() == Source.HEALTHY_RUNTIME)
                .toList();
        List<Episode> failedEpisodes = episodes.stream()
                .filter(episode -> episode.source() == Source.FAILED_RUNTIME)
                .toList();

        long healthyWarningSamples = warnings.stream()
                .filter(warning -> warning.source() == Source.HEALTHY_RUNTIME)
                .count();
        long failedWarningSamples = warnings.stream()
                .filter(warning -> warning.source() == Source.FAILED_RUNTIME)
                .count();

        long healthyWarningGames = healthyGames.stream()
                .filter(game -> game.warningSamples() > 0)
                .count();
        long failedDetectedGames = failedGames.stream()
                .filter(game -> game.warningSamples() > 0)
                .count();
        long failedMissedGames = failedGames.size() - failedDetectedGames;

        long healthyRecoveredEpisodes = healthyEpisodes.stream()
                .filter(Episode::recovered)
                .count();
        long healthyCensoredEpisodes = healthyEpisodes.stream()
                .filter(Episode::terminalOrCensored)
                .count();
        long failedRecoveredEpisodes = failedEpisodes.stream()
                .filter(Episode::recovered)
                .count();
        long failedTerminalEpisodes = failedEpisodes.stream()
                .filter(Episode::terminalOrCensored)
                .count();

        int healthyMaxEpisodeLength = healthyEpisodes.stream()
                .mapToInt(Episode::length)
                .max()
                .orElse(0);
        double healthyAverageEpisodeLength = healthyEpisodes.stream()
                .mapToInt(Episode::length)
                .average()
                .orElse(0.0);
        int failedMaxEpisodeLength = failedEpisodes.stream()
                .mapToInt(Episode::length)
                .max()
                .orElse(0);
        double failedAverageEpisodeLength = failedEpisodes.stream()
                .mapToInt(Episode::length)
                .average()
                .orElse(0.0);

        List<Integer> failedLeadTimes = failedGames.stream()
                .filter(game -> game.firstWarningLead() >= 0)
                .map(GameObservation::firstWarningLead)
                .toList();
        int minLead = failedLeadTimes.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int maxLead = failedLeadTimes.stream().mapToInt(Integer::intValue).max().orElse(-1);
        double averageLead = failedLeadTimes.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(-1.0);

        long failedGamesWithTerminalEpisode = failedGames.stream()
                .filter(GameObservation::terminalOrCensoredEpisode)
                .count();

        System.out.printf(
                Locale.ROOT,
                "# recovery_reserve_persistence_result "
                        + "healthy_games=%d failed_games=%d "
                        + "healthy_warning_games=%d failed_detected_games=%d failed_missed_games=%d "
                        + "healthy_warning_samples=%d failed_warning_samples=%d "
                        + "healthy_episodes=%d healthy_recovered_episodes=%d "
                        + "healthy_censored_episodes=%d healthy_avg_episode_length=%.3f "
                        + "healthy_max_episode_length=%d "
                        + "failed_episodes=%d failed_recovered_episodes=%d "
                        + "failed_terminal_episodes=%d failed_games_with_terminal_episode=%d "
                        + "failed_avg_episode_length=%.3f failed_max_episode_length=%d "
                        + "min_first_warning_lead=%d avg_first_warning_lead=%.3f "
                        + "max_first_warning_lead=%d%n",
                healthyGames.size(),
                failedGames.size(),
                healthyWarningGames,
                failedDetectedGames,
                failedMissedGames,
                healthyWarningSamples,
                failedWarningSamples,
                healthyEpisodes.size(),
                healthyRecoveredEpisodes,
                healthyCensoredEpisodes,
                healthyAverageEpisodeLength,
                healthyMaxEpisodeLength,
                failedEpisodes.size(),
                failedRecoveredEpisodes,
                failedTerminalEpisodes,
                failedGamesWithTerminalEpisode,
                failedAverageEpisodeLength,
                failedMaxEpisodeLength,
                minLead,
                averageLead,
                maxLead);
    }

    enum Source {
        HEALTHY_RUNTIME("healthy-runtime"),
        FAILED_RUNTIME("failed-runtime");

        private final String configValue;

        Source(String configValue) {
            this.configValue = configValue;
        }
    }

    record WarningSample(
            Source source,
            long seed,
            int position,
            int distanceToFailure,
            String reason,
            RecoveryRobustnessBenchmark.Probe probe) {

        WarningSample {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(probe, "probe");
            if (position <= 0 || distanceToFailure < -1) {
                throw new IllegalArgumentException("invalid persistence warning sample");
            }
            if (source == Source.FAILED_RUNTIME && distanceToFailure < 0) {
                throw new IllegalArgumentException(
                        "failed runtime warning requires failure distance");
            }
            if (source == Source.HEALTHY_RUNTIME && distanceToFailure != -1) {
                throw new IllegalArgumentException(
                        "healthy runtime warning must not have failure distance");
            }
        }
    }

    record Episode(
            Source source,
            long seed,
            int startPosition,
            int endPosition,
            int length,
            boolean recovered,
            boolean terminalOrCensored,
            int startDistanceToFailure,
            int endDistanceToFailure) {

        Episode {
            Objects.requireNonNull(source, "source");
            if (startPosition <= 0
                    || endPosition < startPosition
                    || length != endPosition - startPosition + 1
                    || recovered == terminalOrCensored) {
                throw new IllegalArgumentException("invalid warning episode");
            }
            if (source == Source.HEALTHY_RUNTIME
                    && (startDistanceToFailure != -1 || endDistanceToFailure != -1)) {
                throw new IllegalArgumentException(
                        "healthy episode must not have failure distance");
            }
            if (source == Source.FAILED_RUNTIME
                    && (startDistanceToFailure < 0
                            || endDistanceToFailure < 0
                            || startDistanceToFailure < endDistanceToFailure)) {
                throw new IllegalArgumentException(
                        "failed episode requires ordered failure distances");
            }
        }
    }

    record GameObservation(
            Source source,
            long seed,
            int finalPosition,
            int warningSamples,
            int episodes,
            int maxEpisodeLength,
            boolean terminalOrCensoredEpisode,
            int firstWarningPosition,
            int firstWarningLead) {

        GameObservation {
            Objects.requireNonNull(source, "source");
            if (finalPosition < 0
                    || warningSamples < 0
                    || episodes < 0
                    || maxEpisodeLength < 0
                    || firstWarningPosition < -1
                    || firstWarningLead < -1) {
                throw new IllegalArgumentException("invalid persistence game observation");
            }
            if ((warningSamples == 0) != (firstWarningPosition == -1)) {
                throw new IllegalArgumentException(
                        "first warning position must match warning presence");
            }
            if (source == Source.HEALTHY_RUNTIME && firstWarningLead != -1) {
                throw new IllegalArgumentException(
                        "healthy game must not have failure lead-time");
            }
        }

        static GameObservation from(
                Source source,
                long seed,
                int finalPosition,
                List<Integer> warningPositions,
                List<Episode> episodes) {
            int maxEpisodeLength = episodes.stream()
                    .mapToInt(Episode::length)
                    .max()
                    .orElse(0);
            boolean terminalOrCensoredEpisode = episodes.stream()
                    .anyMatch(Episode::terminalOrCensored);
            int firstWarningPosition = warningPositions.isEmpty()
                    ? -1
                    : warningPositions.getFirst();
            int firstWarningLead = source == Source.FAILED_RUNTIME
                    && firstWarningPosition >= 0
                    ? finalPosition - firstWarningPosition
                    : -1;
            return new GameObservation(
                    source,
                    seed,
                    finalPosition,
                    warningPositions.size(),
                    episodes.size(),
                    maxEpisodeLength,
                    terminalOrCensoredEpisode,
                    firstWarningPosition,
                    firstWarningLead);
        }
    }

    private record Configuration(
            int games,
            int maxPieces,
            long seed) {

        static Configuration fromEnvironment() {
            return new Configuration(
                    positiveInt(GAMES_ENV, DEFAULT_GAMES),
                    positiveInt(MAX_PIECES_ENV, DEFAULT_MAX_PIECES),
                    longValue(SEED_ENV, DEFAULT_SEED));
        }

        private static int positiveInt(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be greater than 0");
            }
            return parsed;
        }

        private static long longValue(String name, long defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank()
                    ? defaultValue
                    : Long.parseLong(value);
        }
    }
}
