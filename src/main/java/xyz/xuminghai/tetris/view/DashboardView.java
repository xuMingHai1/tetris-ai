package xyz.xuminghai.tetris.view;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Circle;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.application.HostServices;
import xyz.xuminghai.tetris.ai.AiAction;
import xyz.xuminghai.tetris.ai.AiPlanningAgentFactory;
import xyz.xuminghai.tetris.ai.ShapeProgress;
import xyz.xuminghai.tetris.ai.ShapeTarget;
import xyz.xuminghai.tetris.game.GameWorld;
import xyz.xuminghai.tetris.core.BoardPosition;
import xyz.xuminghai.tetris.core.Cell;
import xyz.xuminghai.tetris.util.Version;
import javafx.util.Duration;
import javafx.scene.text.Font;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.ArrayList;

/** JavaFX layout and read-only presentation for the desktop game and AI controls. */
public final class DashboardView extends BorderPane {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass REFRESHING_HINT = PseudoClass.getPseudoClass("refreshing-hint");

    private final GameWorld world;
    private final String agent;
    private final String objective;
    private final int boardCellSide;
    private final double viewportWidth;
    private final Label score = label("score-value");
    private final Label scoreGain = label("score-gain");
    private final Pane scoreFrame = new Pane(score, scoreGain);
    private final CheckBox reducedMotion = new CheckBox();
    private final ScaleTransition scorePulse = new ScaleTransition(Duration.millis(220), score);
    private final FadeTransition gainFade = new FadeTransition(Duration.millis(540), scoreGain);
    private final TranslateTransition gainLift = new TranslateTransition(Duration.millis(540), scoreGain);
    private final ParallelTransition gainAnimation = new ParallelTransition(gainFade, gainLift);
    private final Pane sweepLayer = new Pane();
    private RowShiftView rowShiftView;
    private FallingPieceView fallingPieceView;
    private ParallelTransition sweepAnimation;
    private double cellPitch;
    private boolean awaitingClearSweep;
    private final Label lines = label("stat-value");
    private final Label level = label("stat-value");
    private final Label duration = label("duration-value");
    private final Label controller = label("controller-value");
    private final Label stageStatus = label("stage-status");
    private final Label boardHint = label("stage-hint");
    private final Label overlayTitle = label("overlay-title");
    private final Label overlayMessage = label("overlay-message");
    private final Label aiStatus = label("ai-status");
    private final Label aiMessage = label("ai-message");
    private final Label title = label("page-title");
    private final Label subtitle = label("page-subtitle");
    private final Label nextTitle = label("section-label");
    private final Label scoreTitle = label("section-label");
    private final Label linesTitle = label("section-label");
    private final Label levelTitle = label("section-label");
    private final Label durationTitle = label("section-label");
    private final Label agentTitle = label("cockpit-title");
    private final Label goalTitle = label("section-heading");
    private final Label goal = label("goal-text");
    private final Label agentBadge = label("agent-badge");
    private final Label actionsTitle = label("section-heading");
    private final Label keys = label("keyboard-note");
    private final Button manualButton = button("mode-button");
    private final Button aiButton = button("mode-button");
    private final Button startButton = button("primary-button");
    private final Button takeoverButton = button("takeover-button");
    private final Button coachButton = button("quiet-button");
    private final Button placeAdviceButton = button("takeover-button");
    private final PauseTransition hintRefreshFeedback = new PauseTransition(Duration.millis(150));
    private final Button languageButton = button("quiet-button");
    private final Tooltip startTooltip = new Tooltip();
    private final Tooltip takeoverTooltip = new Tooltip();
    private final Label emptyActions = label("empty-actions");
    private final VBox actions = new VBox(3);
    private final ScrollPane actionScroll = new ScrollPane(actions);
    private final VBox overlayContent = new VBox(6, overlayTitle, overlayMessage);
    private final StackPane boardWell = new StackPane();
    private final StackPane overlay = new StackPane(overlayContent);
    private GameWorld.AiDisplay shownDisplay;
    private List<AiAction> shownPlan = List.of();
    private List<ActionGroup> actionGroups = List.of();
    private final List<ActionRow> actionRows = new ArrayList<>();
    private int followedActionRow = -1;
    private boolean waitingForReplacementHint;
    private Integer lastHintClearedLines;
    private boolean everStarted;

    /** Uses the same startup configuration already validated by the planning-agent factory. */
    public static DashboardView fromEnvironment(GameWorld world, HostServices hostServices,
                                                double viewportWidth, double viewportHeight) {
        String configuredAgent = System.getenv().getOrDefault("TETRIS_AI_AGENT", "heuristic")
                .trim().toLowerCase(Locale.ROOT);
        String agentLabel = switch (configuredAgent) {
            case "jev" -> "Jev AI";
            case "jev-action" -> "Jev Action";
            case "action" -> "Action AI";
            default -> "Heuristic";
        };
        String objective = System.getenv()
                .getOrDefault(AiPlanningAgentFactory.OBJECTIVE_ENV, "survival")
                .trim().toLowerCase(Locale.ROOT);
        // Reserve space for the reference header, controls and footer before sizing the board.
        int cellSide = Math.max(16, Math.min(25,
                (int) Math.floor((viewportHeight - 360) / world.getRows()) - 1));
        DashboardView view = new DashboardView(world, hostServices, agentLabel, objective,
                cellSide, viewportWidth);
        if (viewportHeight < 900) view.getStyleClass().add("compact-height");
        return view;
    }

    public DashboardView(GameWorld world, HostServices hostServices,
                         String configuredAgent, String configuredObjective,
                         int boardCellSide, double viewportWidth) {
        this.world = world;
        this.agent = configuredAgent;
        aiButton.setText(agent);
        this.objective = configuredObjective;
        this.boardCellSide = boardCellSide;
        this.viewportWidth = viewportWidth;
        startButton.setTooltip(startTooltip);
        takeoverButton.setTooltip(takeoverTooltip);
        reducedMotion.setId("reduced-motion");
        getStyleClass().add("dashboard");
        getStylesheets().add("css/dashboard.css");

        setTop(header());
        ScrollPane mainScroll = new ScrollPane(content(hostServices));
        mainScroll.setFitToWidth(true);
        mainScroll.setPannable(true);
        mainScroll.getStyleClass().add("main-scroll");
        setCenter(mainScroll);
        setBottom(footer(hostServices));

        score.textProperty().bind(world.scoreProperty().map(NumberFormat.getIntegerInstance()::format));
        score.textProperty().addListener((_, _, value) -> score.setStyle(
                "-fx-font-size: " + (value.length() > 7 ? 22 : value.length() > 5 ? 29 : 38) + "px"));
        scorePulse.setFromX(1.06);
        scorePulse.setFromY(1.06);
        scorePulse.setToX(1);
        scorePulse.setToY(1);
        gainFade.setFromValue(1);
        gainFade.setToValue(0);
        gainLift.setFromY(0);
        gainLift.setToY(-24);
        gainAnimation.setOnFinished(_ -> scoreGain.setVisible(false));
        world.scoreProperty().addListener((_, oldScore, newScore) -> {
            long gain = newScore.longValue() - oldScore.longValue();
            if (gain > 0 && !reducedMotion.isSelected()) {
                showScoreGain(gain);
            }
            else {
                stopVisualEffects();
            }
        });
        world.linesProperty().addListener((_, oldLines, newLines) -> {
            if (newLines.intValue() > oldLines.intValue()) awaitingClearSweep = true;
        });
        world.clearCellProperty().addListener((_, _, cells) -> {
            if (awaitingClearSweep && cells != null && !cells.isEmpty()) {
                awaitingClearSweep = false;
                showRowSweep(cells);
            }
        });
        lines.textProperty().bind(world.linesProperty().asString());
        level.textProperty().bind(world.levelProperty().asString());
        duration.textProperty().bind(world.gameDurationProperty().map(time ->
                "%02d:%02d:%02d".formatted(
                        time.toHours(), time.toMinutesPart(), time.toSecondsPart())));
        world.aiEnabledProperty().addListener((_, _, _) -> refresh());
        world.activeDisplayProperty().addListener((_, _, active) -> {
            if (active) everStarted = true;
            else stopVisualEffects();
            refresh();
        });
        world.gameOverDisplayProperty().addListener((_, _, over) -> {
            if (over) stopVisualEffects();
            refresh();
        });
        world.aiDisplayProperty().addListener((_, _, _) -> refresh());
        world.coachEnabledProperty().addListener((_, _, _) -> refresh());
        world.coachAdviceProperty().addListener((_, _, _) -> refresh());
        world.languageProperty().addListener((_, _, _) -> refreshLanguage());
        refreshLanguage();
    }

    /** Makes game shortcuts immediately usable after a mouse or keyboard game-control action. */
    public void requestGameFocus() {
        boardWell.requestFocus();
    }

    private VBox header() {
        GridPane blocks = new GridPane();
        blocks.setHgap(2);
        blocks.setVgap(2);
        blocks.setAlignment(Pos.CENTER);
        for (int col = 0; col < 3; col++) {
            Rectangle block = new Rectangle(7, 7);
            block.getStyleClass().add("brand-block");
            block.setArcWidth(2);
            block.setArcHeight(2);
            blocks.add(block, col, 1);
        }
        Rectangle crown = new Rectangle(7, 7);
        crown.getStyleClass().add("brand-block");
        crown.setArcWidth(2);
        crown.setArcHeight(2);
        blocks.add(crown, 1, 0);
        StackPane mark = new StackPane(blocks);
        mark.setMinSize(32, 32);
        mark.setPrefSize(32, 32);
        mark.setMaxSize(32, 32);
        mark.getStyleClass().add("brand-mark");
        Label brand = label("brand");
        brand.setText("TETRIS");
        Label suffix = label("brand-suffix");
        suffix.setText("/ AI");
        HBox identity = new HBox(12, mark, brand, suffix);
        identity.setAlignment(Pos.CENTER_LEFT);
        languageButton.setOnAction(_ -> world.switchLanguage());
        languageButton.setTooltip(new Tooltip("Ctrl + Tab"));
        HBox top = new HBox(identity, new Region(), languageButton);
        top.getStyleClass().add("top-bar");
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        top.setAlignment(Pos.CENTER_LEFT);

        manualButton.setOnAction(_ -> {
            if (world.aiEnabledProperty().get()) world.toggleAi();
            requestGameFocus();
        });
        aiButton.setOnAction(_ -> {
            if (!world.aiEnabledProperty().get()) world.toggleAi();
            requestGameFocus();
        });
        HBox modes = new HBox(manualButton, aiButton);
        modes.getStyleClass().add("mode-group");
        VBox heading = new VBox(3, title, subtitle);
        HBox toolbar = new HBox(heading, new Region(), modes);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(toolbar.getChildren().get(1), Priority.ALWAYS);
        return new VBox(top, toolbar);
    }

    private Pane content(HostServices hostServices) {
        VBox left = sidebar();
        VBox center = stage();
        VBox right = cockpit(hostServices);
        // At the largest cell size, the board and fixed side panels need about 810 px.
        if (viewportWidth < 820) {
            HBox game = new HBox(24, left, center);
            game.setAlignment(Pos.TOP_CENTER);
            VBox compact = new VBox(20, game, right);
            compact.getStyleClass().add("main-content");
            compact.setAlignment(Pos.TOP_CENTER);
            return compact;
        }
        HBox row = new HBox(24, left, center, right);
        row.getStyleClass().add("main-content");
        row.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(center, Priority.ALWAYS);
        return row;
    }

    private VBox sidebar() {
        score.getStyleClass().add("numeric");
        HBox stats = new HBox(21, new VBox(5, linesTitle, lines), new VBox(5, levelTitle, level));
        stats.getStyleClass().add("stats-row");
        NextBlockView next = new NextBlockView(world.nextTetrisProperty());
        StackPane nextFrame = new StackPane();
        nextFrame.getStyleClass().add("next-frame");
        Label controlTitle = label("section-label");
        controlTitle.setText("CONTROL");
        scoreFrame.setPrefSize(142, 54);
        scoreGain.setLayoutX(85);
        scoreGain.setLayoutY(0);
        scoreGain.setVisible(false);
        Label nextIndex = label("next-index");
        nextIndex.setText("01");
        HBox nextCard = new HBox(10, nextIndex, new Region(), next);
        nextCard.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nextCard.getChildren().get(1), Priority.ALWAYS);
        nextFrame.getChildren().setAll(nextCard);
        VBox preview = new VBox(14, nextTitle, nextFrame);
        VBox control = new VBox(5, controlTitle, controller);
        control.getStyleClass().add("control-section");
        VBox clock = new VBox(5, durationTitle, duration);
        VBox side = new VBox(24, new VBox(10, scoreTitle, scoreFrame), stats, preview, control, clock);
        side.getStyleClass().add("score-panel");
        side.setPrefWidth(142);
        side.setMinWidth(142);
        return side;
    }

    private VBox stage() {
        GameContextView board = new GameContextView(world, boardCellSide, true);
        GhostLandingView ghost = new GhostLandingView(world, board,
                objective.equals("build-shape"), reducedMotion::isSelected);
        rowShiftView = new RowShiftView(world, board, reducedMotion::isSelected);
        fallingPieceView = new FallingPieceView(world, board, reducedMotion::isSelected);
        cellPitch = (board.getWidth() - 1) / world.getCols();
        sweepLayer.setPrefSize(board.getWidth(), board.getHeight());
        sweepLayer.setMaxSize(board.getWidth(), board.getHeight());
        sweepLayer.setClip(new Rectangle(board.getWidth(), board.getHeight()));
        sweepLayer.setMouseTransparent(true);
        overlay.getStyleClass().add("board-overlay");
        overlay.setVisible(false);
        overlay.setMouseTransparent(true);
        overlayContent.setAlignment(Pos.CENTER);
        overlayMessage.setWrapText(true);
        overlayMessage.setMaxWidth(210);
        overlayMessage.setAlignment(Pos.CENTER);
        boardWell.getChildren().setAll(board, ghost, fallingPieceView, sweepLayer, rowShiftView, overlay);
        boardWell.setFocusTraversable(true);
        boardWell.setOnMousePressed(_ -> requestGameFocus());
        boardWell.getStyleClass().add("board-frame");
        boardWell.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Circle stageDot = new Circle(3);
        stageDot.getStyleClass().add("status-dot");
        HBox boardHead = new HBox(6, stageDot, stageStatus);
        boardHead.getStyleClass().add("stage-head");
        boardHead.setMaxWidth(board.getWidth() + 22);
        startButton.setOnAction(_ -> {
            world.startOrPauseGame();
            requestGameFocus();
        });
        startButton.setMaxWidth(Double.MAX_VALUE);
        HBox controls = new HBox(startButton);
        controls.setMaxWidth(board.getWidth() + 22);
        HBox.setHgrow(startButton, Priority.ALWAYS);
        VBox stage = new VBox(12, boardHead, boardWell, controls, boardHint);
        stage.getStyleClass().add("stage");
        stage.setAlignment(Pos.TOP_CENTER);
        return stage;
    }

    private VBox cockpit(HostServices hostServices) {
        HBox heading = new HBox(agentTitle, new Region(), agentBadge);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heading.getChildren().get(1), Priority.ALWAYS);
        heading.getStyleClass().add("cockpit-head");

        goal.setWrapText(true);
        Rectangle goalIcon = new Rectangle(7, 7);
        goalIcon.setRotate(45);
        goalIcon.getStyleClass().add("goal-icon");
        HBox goalCard = new HBox(10, goalIcon, goal);
        goalCard.setAlignment(Pos.CENTER_LEFT);
        goalCard.getStyleClass().add("goal-value");
        HBox.setHgrow(goal, Priority.ALWAYS);
        VBox goalSection = new VBox(12, goalTitle, goalCard);
        goalSection.getStyleClass().add("cockpit-section");
        actionScroll.setFitToWidth(true);
        updateActionViewportHeight();
        getStyleClass().addListener((InvalidationListener) _ -> updateActionViewportHeight());
        // Keep the text width stable even when the current plan does not overflow.
        actionScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        emptyActions.setWrapText(true);
        actions.getChildren().add(emptyActions);
        actionScroll.getStyleClass().add("action-scroll");
        VBox actionSection = new VBox(12, actionsTitle, actionScroll);
        actionSection.getStyleClass().add("cockpit-section");

        takeoverButton.setOnAction(_ -> {
            world.toggleAi();
            requestGameFocus();
        });
        takeoverButton.setMaxWidth(Double.MAX_VALUE);
        takeoverButton.setId("auto-play-toggle");
        coachButton.setId("coach-toggle");
        placeAdviceButton.setId("coach-place");
        hintRefreshFeedback.setOnFinished(_ ->
                placeAdviceButton.pseudoClassStateChanged(REFRESHING_HINT, false));
        coachButton.setMaxWidth(Double.MAX_VALUE);
        placeAdviceButton.setMaxWidth(Double.MAX_VALUE);
        coachButton.setOnAction(_ -> {
            world.toggleCoach();
            requestGameFocus();
        });
        placeAdviceButton.setOnAction(_ -> {
            world.executeCoachAdvice();
            requestGameFocus();
        });
        Circle statusDot = new Circle(3);
        statusDot.getStyleClass().add("status-dot");
        HBox status = new HBox(7, statusDot, aiStatus);
        status.setAlignment(Pos.CENTER_LEFT);
        aiStatus.setWrapText(true);
        VBox body = new VBox(12, status, aiMessage, goalSection,
                actionSection, coachButton, placeAdviceButton, takeoverButton);
        body.getStyleClass().add("cockpit-body");
        aiMessage.setWrapText(true);
        aiMessage.setMinHeight(42);
        aiMessage.setPrefHeight(42);
        aiMessage.setMaxHeight(42);
        VBox card = new VBox(heading, body);
        card.getStyleClass().add("cockpit-card");
        if (objective.equals("build-shape")) {
            TitledPane creativePane = new TitledPane();
            creativePane.textProperty().bind(world.languageProperty().map(locale ->
                    locale.getLanguage().equals("zh") ? "创意目标 · HEART" : "Creative goal · HEART"));
            creativePane.setExpanded(false);
            creativePane.getStyleClass().add("creative-pane");
            Canvas target = targetPreview();
            Label shapeProgress = label("shape-progress");
            var boardProgress = world.settledCellsProperty().map(this::heartProgress);
            shapeProgress.textProperty().bind(Bindings.createStringBinding(() -> {
                ShapeProgress progress = boardProgress.getValue();
                String coverage = progress.matchedRequiredCells() + "/" + progress.requiredCells();
                return isChinese() ? "目标覆盖 " + coverage + " · 背景侵入 " + progress.forbiddenOccupiedCells()
                        : "Coverage " + coverage + " · Intrusions " + progress.forbiddenOccupiedCells();
            }, boardProgress, world.languageProperty()));
            VBox creativeContent = new VBox(10, target, shapeProgress);
            creativeContent.getStyleClass().add("creative-content");
            creativePane.setContent(creativeContent);
            card.getChildren().add(creativePane);
        }

        TitledPane shortcuts = new TitledPane();
        shortcuts.setExpanded(false);
        shortcuts.setContent(new VBox(10, keys, reducedMotion));
        shortcuts.getStyleClass().add("shortcuts");
        shortcuts.textProperty().bind(world.languageProperty().map(locale ->
                locale.getLanguage().equals("zh") ? "操作与快捷键" : "Controls"));
        keys.setWrapText(true);
        reducedMotion.setOnAction(_ -> {
            if (reducedMotion.isSelected()) stopVisualEffects();
        });

        Button minus = button("stepper-button");
        minus.setText("−");
        Tooltip slower = new Tooltip();
        slower.textProperty().bind(world.languageProperty().map(locale ->
                locale.getLanguage().equals("zh") ? "降低等级与下落速度（−）" : "Decrease level and speed (−)"));
        minus.setTooltip(slower);
        minus.accessibleTextProperty().bind(slower.textProperty());
        minus.setOnAction(_ -> {
            world.levelMinus();
            requestGameFocus();
        });
        Button plus = button("stepper-button");
        plus.setText("+");
        Tooltip faster = new Tooltip();
        faster.textProperty().bind(world.languageProperty().map(locale ->
                locale.getLanguage().equals("zh") ? "提高等级与下落速度（+）" : "Increase level and speed (+)"));
        plus.setTooltip(faster);
        plus.accessibleTextProperty().bind(faster.textProperty());
        plus.setOnAction(_ -> {
            world.levelPlus();
            requestGameFocus();
        });
        HBox levelControls = new HBox(8, minus, plus);
        levelControls.getStyleClass().add("level-controls");
        HBox auxiliary = new HBox(10, shortcuts, levelControls);
        auxiliary.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(shortcuts, Priority.ALWAYS);
        HBox.setHgrow(levelControls, Priority.ALWAYS);
        shortcuts.setMaxWidth(Double.MAX_VALUE);
        // Expanded keyboard notes need the full panel width; collapsed controls share one row.
        VBox right = new VBox(15, card, auxiliary);
        shortcuts.expandedProperty().addListener((_, _, expanded) -> {
            if (expanded) {
                auxiliary.getChildren().remove(shortcuts);
                right.getChildren().add(1, shortcuts);
            }
            else {
                right.getChildren().remove(shortcuts);
                auxiliary.getChildren().addFirst(shortcuts);
            }
        });
        right.getStyleClass().add("right-panel");
        right.setPrefWidth(260);
        right.setMinWidth(260);
        return right;
    }

    private HBox footer(HostServices hostServices) {
        Label footerText = label("footer-text");
        footerText.setText("TETRIS / AI");
        Hyperlink version = new Hyperlink(Version.VERSION);
        version.getStyleClass().add("version-link");
        version.setOnAction(_ -> hostServices.showDocument(Version.RELEASE_URL));
        Label dimensions = label("footer-text");
        dimensions.setText(world.getCols() + " × " + world.getRows());
        HBox footer = new HBox(footerText, version, new Region(), dimensions);
        footer.getStyleClass().add("footer");
        HBox.setHgrow(footer.getChildren().get(2), Priority.ALWAYS);
        return footer;
    }

    private ShapeProgress heartProgress(List<BoardPosition> settled) {
        boolean[][] occupied = new boolean[world.getRows()][world.getCols()];
        for (BoardPosition cell : settled) occupied[cell.row()][cell.col()] = true;
        return ShapeTarget.HEART.progress(occupied);
    }

    private boolean isChinese() {
        return world.languageProperty().get().getLanguage().equals("zh");
    }

    private void refreshLanguage() {
        boolean zh = isChinese();
        useSystemFont(zh);
        title.setText(zh ? "自由对局" : "Free play");
        subtitle.setText((zh ? "经典模式 · " : "Classic mode · ")
                + world.getCols() + " × " + world.getRows());
        scoreTitle.setText(zh ? "本局得分" : "SCORE");
        linesTitle.setText(zh ? "消行" : "LINES");
        levelTitle.setText(zh ? "等级" : "LEVEL");
        durationTitle.setText(zh ? "游戏时长" : "DURATION");
        nextTitle.setText(zh ? "下一块" : "NEXT PIECE");
        agentTitle.setText(zh ? "AI 驾驶舱" : "AI cockpit");
        goalTitle.setText(zh ? "当前目标" : "Objective");
        actionsTitle.setText(zh ? "动作计划" : "Action plan");
        languageButton.setText(zh ? "EN" : "中文");
        manualButton.setText(zh ? "手动" : "Manual");
        placeAdviceButton.setText(zh ? "按建议放这一块" : "Place this piece as suggested");
        keys.setText(zh
                ? "A / D  左右移动   ·   S  下移\n← / →  旋转   ·   Space / Esc  开始或暂停\nF2  AI 开关   ·   + / −  调整等级\nCtrl + Tab  切换语言"
                : "A / D  Move   ·   S  Soft drop\n← / →  Rotate   ·   Space / Esc  Start or pause\nF2  Toggle AI   ·   + / −  Level\nCtrl + Tab  Language");
        reducedMotion.setText(zh ? "减少界面动效" : "Reduce UI motion");
        takeoverTooltip.setText(zh ? "F2：切换 AI / 手动控制" : "F2: toggle AI / manual control");
        refresh();
    }

    private void refresh() {
        boolean zh = isChinese();
        boolean enabled = world.aiEnabledProperty().get();
        boolean coaching = world.coachEnabledProperty().get();
        GameWorld.CoachAdvice advice = world.coachAdviceProperty().get();
        boolean active = world.activeDisplayProperty().get();
        boolean over = world.gameOverDisplayProperty().get();
        GameWorld.AiDisplay display = world.aiDisplayProperty().get();
        String phase = display.phase();
        boolean showCoach = coaching && !over;
        boolean previousPlan = active && !over && !shownPlan.isEmpty()
                && ((enabled || coaching) && phase.equals("thinking")
                || coaching && phase.equals("advice") && advice == null);

        agentBadge.setText(showCoach ? (zh ? "本地" : "LOCAL") : agent.toUpperCase(Locale.ROOT));
        manualButton.pseudoClassStateChanged(SELECTED, !enabled);
        aiButton.pseudoClassStateChanged(SELECTED, enabled);
        controller.setText(enabled ? agent : (zh ? "手动控制" : "Manual"));
        goal.setText(showCoach ? (zh ? "本地生存建议 · 由你控制" : "Local survival hints · you control") : switch (objective) {
            case "build-shape" -> zh ? "心形构造 · 生存优先" : "Build a heart · survival first";
            case "tuck-hunter" -> zh ? "凹槽探索 · 生存优先" : "Tuck hunting · survival first";
            default -> zh ? "优先生存，持续消行" : "Survive and clear lines";
        });
        boolean finishing = over && active;
        startButton.setDisable(finishing);
        startButton.setText(finishing ? (zh ? "游戏结束" : "Game over")
                : over ? (zh ? "重新开始" : "Restart")
                : active ? (zh ? "暂停" : "Pause")
                : everStarted ? (zh ? "继续" : "Resume") : (zh ? "开始游戏" : "Start game"));
        takeoverButton.setText(enabled ? (zh ? "接管游戏 · 手动" : "Take over · manual")
                : (zh ? "交给 AI 操作" : "Let AI play"));
        coachButton.setText(coaching ? (zh ? "关闭 AI 建议" : "Turn off AI hints")
                : (zh ? "开启 AI 建议" : "Turn on AI hints"));
        updateHintButton(showCoach && previousPlan && advice == null, showCoach && active && advice != null);

        overlay.setVisible(!active);
        overlayTitle.setText(over ? (zh ? "游戏结束" : "Game over")
                : everStarted ? (zh ? "已暂停" : "Paused")
                : (zh ? "准备开始" : "Ready to play"));
        overlayMessage.setText(over ? (zh ? "重新开始一局新的对局" : "Start a new game when ready")
                : everStarted ? (zh ? "按 Space / Esc 或点击继续" : "Space / Esc or Resume to continue")
                : (zh ? "选择控制方式，开始对局" : "Choose a controller and start playing"));
        stageStatus.setText(over ? (zh ? "游戏结束" : "Game over")
                : active ? (zh ? "对局进行中" : "Game in progress")
                : everStarted ? (zh ? "已暂停" : "Paused")
                : (zh ? "准备就绪" : "Ready"));

        // Keep the last published estimate readable while this coaching session recalculates.
        if (!showCoach || !active) lastHintClearedLines = null;
        else if (advice != null) lastHintClearedLines = advice.clearedLines();
        if (showCoach) {
            aiStatus.setText(!active ? (zh ? "建议已暂停" : "Hints paused")
                    : phase.equals("executing") ? (zh ? "只执行当前块" : "Playing this piece only")
                    : phase.equals("failed") ? (zh ? "建议暂不可用" : "Hint unavailable")
                    : (zh ? "AI 建议已开启" : "AI hints on"));
            aiMessage.setText((zh ? "虚线标出推荐落点\n最近建议 · 预计消行 "
                    : "Dashed outline marks the hint\nLatest hint · expected lines: ")
                    + (lastHintClearedLines == null ? "—" : lastHintClearedLines.toString()));
        }
        else {
            refreshAiMessage(phase, zh, active, over, enabled, previousPlan);
        }
        boardHint.setText(finishing ? (zh ? "正在播放结束动画" : "Playing the end animation")
                : over ? (zh ? "Space / Esc · 重新开始" : "Space / Esc · restart")
                : (zh ? "Space / Esc · 开始或暂停" : "Space / Esc · start or pause"));
        startTooltip.setText(boardHint.getText());
        refreshActions(display, zh, previousPlan, active && !over && (enabled || coaching));
    }

    private void updateHintButton(boolean replacingHint, boolean canPlace) {
        if (replacingHint && !placeAdviceButton.isDisabled()) {
            // Keep quick recomputations from flashing while the action itself is already disabled.
            placeAdviceButton.pseudoClassStateChanged(REFRESHING_HINT, true);
            hintRefreshFeedback.playFromStart();
        }
        else if (!replacingHint && waitingForReplacementHint) {
            hintRefreshFeedback.stop();
            placeAdviceButton.pseudoClassStateChanged(REFRESHING_HINT, false);
        }
        waitingForReplacementHint = replacingHint;
        placeAdviceButton.setDisable(!canPlace);
    }

    private void refreshAiMessage(String phase, boolean zh, boolean active, boolean over,
                                  boolean enabled, boolean previousPlan) {
        aiStatus.setText(over ? (zh ? "对局结束" : "Game over")
                : !enabled ? (zh ? "手动控制" : "Manual control")
                : !active ? (everStarted ? (zh ? "AI 已暂停" : "AI paused")
                        : (zh ? "AI 已就绪" : "AI ready")) : switch (phase) {
            case "thinking" -> zh ? "正在选择落点" : "Choosing a placement";
            case "next-piece" -> zh ? "下一块起生效" : "Starts with next piece";
            case "executing" -> zh ? "正在执行" : "Executing";
            case "fallback" -> zh ? "已切换本地策略" : "Local fallback used";
            case "failed" -> zh ? "决策失败" : "Decision failed";
            case "completed" -> zh ? "本步完成" : "Plan completed";
            default -> zh ? "等待决策" : "Waiting for a plan";
        });
        aiMessage.setText(over && active ? (zh ? "结束动画播放完毕后，可重新开始。" : "You can restart after the end animation.")
                : over ? (zh ? "本局已结束，可重新开始。" : "Game finished. Start again when ready.")
                : !enabled ? (zh ? "使用 A / D 移动，← / → 旋转。" : "Use A / D to move and ← / → to rotate.")
                : !active ? (everStarted ? (zh ? "继续对局后，AI 将重新选择落点。" : "Resume to let AI choose a new placement.")
                        : (zh ? "开始对局后，AI 将自动操作。" : "Start the game to let AI play."))
                : phase.equals("thinking") ? (previousPlan
                        ? (zh ? "下方是上次动作，新的落点正在计算。" : "Previous plan below. Finding a new placement.")
                        : (zh ? "正在为当前方块寻找落点。" : "Finding a placement for this piece."))
                : phase.equals("next-piece") ? (zh ? "当前方块仍可手动操作。" : "You still control the current piece.")
                : phase.equals("fallback") ? (zh ? "本步使用本地策略继续。" : "Continuing with the local strategy.")
                : phase.equals("failed") ? (zh ? "暂时无法决策，可接管游戏。" : "Unable to decide. You can take over.")
                : phase.equals("completed") ? (zh ? "方块已落地，等待下一块。" : "Piece landed. Waiting for the next one.")
                : (zh ? "随时可接管游戏，切回手动操作。" : "Take over at any time to play manually."));
    }

    private void updateActionViewportHeight() {
        double viewportHeight = boardCellSide < 20 ? 90 : getStyleClass().contains("compact-height") ? 120 : 140;
        actionScroll.setPrefViewportHeight(viewportHeight);
        actionScroll.setMinHeight(viewportHeight);
        actionScroll.setPrefHeight(viewportHeight);
        actionScroll.setMaxHeight(viewportHeight);
    }

    private void refreshActions(GameWorld.AiDisplay display, boolean zh, boolean previousPlan, boolean showPlan) {
        // The last plan is presentation only while the next decision is pending.
        GameWorld.AiDisplay visibleDisplay = previousPlan ? shownDisplay : display;
        List<AiAction> plan = showPlan ? visibleDisplay.actions() : List.of();
        boolean planChanged = !shownPlan.equals(plan);
        if (planChanged) {
            shownPlan = plan;
            actionGroups = groupActions(plan);
            resizeActionRows();
        }
        if (!previousPlan) shownDisplay = display;
        emptyActions.setVisible(plan.isEmpty());
        emptyActions.setManaged(plan.isEmpty());
        if (plan.isEmpty()) {
            emptyActions.setText(world.coachEnabledProperty().get()
                    ? (zh ? "建议就绪后显示动作。" : "Moves appear when the hint is ready.")
                    : !world.aiEnabledProperty().get()
                    ? (zh ? "启用 AI 后，在这里查看动作。" : "Enable AI to see its moves here.")
                    : display.phase().equals("thinking") ? (zh ? "正在生成本次计划…" : "Planning the next moves…")
                    : (zh ? "对局运行后显示本次动作。" : "Moves appear when the game is running."));
            if (planChanged) actionScroll.setVvalue(actionScroll.getVmin());
            followedActionRow = -1;
            return;
        }
        int completed = visibleDisplay.completedActions();
        boolean executing = visibleDisplay.phase().equals("executing");
        int activeIndex = -1;
        for (int index = 0; index < actionGroups.size(); index++) {
            ActionGroup group = actionGroups.get(index);
            int count = group.end() - group.start();
            boolean done = completed >= group.end();
            boolean active = !done && completed >= group.start() && executing;
            ActionRow row = actionRows.get(index);
            row.container().pseudoClassStateChanged(PseudoClass.getPseudoClass("done"), done);
            row.container().pseudoClassStateChanged(PseudoClass.getPseudoClass("active"), active);
            row.badge().setText(done ? "✓" : "%02d".formatted(index + 1));
            String text = actionName(group.action(), zh) + (count > 1 ? " × " + count : "");
            int executed = Math.clamp(completed - group.start(), 0, count);
            if (count > 1 && !done && (active || executed > 0)) text += " · " + executed + "/" + count;
            if (!done && visibleDisplay.phase().equals("completed")) text += zh ? " · 已落地" : " · landed";
            row.text().setText(text);
            if (active && !previousPlan) activeIndex = index;
        }
        if (activeIndex >= 0 && (activeIndex != followedActionRow || planChanged)) {
            showActiveAction(actionRows.get(activeIndex).container());
        }
        else if (planChanged && display.phase().equals("advice")) {
            actionScroll.setVvalue(actionScroll.getVmin());
        }
        followedActionRow = activeIndex;
    }

    private void resizeActionRows() {
        while (actionRows.size() > actionGroups.size()) {
            actions.getChildren().remove(actionRows.removeLast().container());
        }
        while (actionRows.size() < actionGroups.size()) {
            Label badge = label("action-number");
            Label text = label("action-text");
            text.setWrapText(true);
            HBox row = new HBox(12, badge, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("action-row");
            HBox.setHgrow(text, Priority.ALWAYS);
            actionRows.add(new ActionRow(row, badge, text));
            actions.getChildren().add(row);
        }
    }

    private void showActiveAction(HBox row) {
        actionScroll.layout();
        double viewport = actionScroll.getViewportBounds().getHeight();
        double overflow = actions.getLayoutBounds().getHeight() - viewport;
        if (viewport <= 0 || overflow <= 0) return;
        double offset = actionScroll.getVvalue() * overflow;
        double top = row.getBoundsInParent().getMinY();
        double bottom = row.getBoundsInParent().getMaxY();
        if (top < offset) {
            actionScroll.setVvalue(Math.clamp(top / overflow, 0, 1));
        }
        else if (bottom > offset + viewport) {
            actionScroll.setVvalue(Math.clamp((bottom - viewport) / overflow, 0, 1));
        }
    }

    private record ActionRow(HBox container, Label badge, Label text) { }

    /** Adjacent equal controls share one visual row; its completion still uses actual action indices. */
    static List<ActionGroup> groupActions(List<AiAction> plan) {
        List<ActionGroup> groups = new ArrayList<>();
        for (int start = 0; start < plan.size();) {
            int end = start + 1;
            while (end < plan.size() && plan.get(end) == plan.get(start)) end++;
            groups.add(new ActionGroup(plan.get(start), start, end));
            start = end;
        }
        return List.copyOf(groups);
    }

    record ActionGroup(AiAction action, int start, int end) { }

    private void useSystemFont(boolean chinese) {
        List<String> candidates = chinese
                ? List.of("Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Segoe UI", "System")
                : List.of("Segoe UI", "Noto Sans", "DejaVu Sans", "System");
        String family = candidates.stream().filter(Font.getFamilies()::contains).findFirst().orElse("System");
        String style = "-fx-font-family: '" + family + "';";
        if (!getStyle().equals(style)) setStyle(style);
    }

    private void showScoreGain(long gain) {
        stopScoreEffects();
        scoreGain.setText("+" + NumberFormat.getIntegerInstance().format(gain));
        scoreGain.setVisible(true);
        scorePulse.playFromStart();
        gainAnimation.playFromStart();
    }

    private void stopVisualEffects() {
        stopScoreEffects();
        clearSweep();
        if (rowShiftView != null) rowShiftView.clear();
        if (fallingPieceView != null) fallingPieceView.finishMotion();
        awaitingClearSweep = false;
    }

    private void stopScoreEffects() {
        scorePulse.stop();
        score.setScaleX(1);
        score.setScaleY(1);
        gainAnimation.stop();
        scoreGain.setVisible(false);
        scoreGain.setOpacity(1);
        scoreGain.setTranslateY(0);
    }

    private void showRowSweep(List<Cell> clearedCells) {
        if (reducedMotion.isSelected()) return;
        clearSweep();
        sweepAnimation = new ParallelTransition();
        for (int row : clearedCells.stream().map(Cell::getRow)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
            Rectangle stripe = new Rectangle(0, row * cellPitch + 1,
                    sweepLayer.getPrefWidth(), cellPitch - 1);
            stripe.setFill(Color.web("#9bb3d6", 0.38));
            sweepLayer.getChildren().add(stripe);
            FadeTransition fade = new FadeTransition(Duration.millis(350), stripe);
            fade.setFromValue(0.8);
            fade.setToValue(0);
            TranslateTransition move = new TranslateTransition(Duration.millis(350), stripe);
            move.setFromX(-18);
            move.setToX(18);
            sweepAnimation.getChildren().add(new ParallelTransition(fade, move));
        }
        sweepAnimation.setOnFinished(_ -> clearSweep());
        sweepAnimation.play();
    }

    private void clearSweep() {
        if (sweepAnimation != null) {
            sweepAnimation.stop();
            sweepAnimation = null;
        }
        sweepLayer.getChildren().clear();
    }

    private static String actionName(AiAction action, boolean zh) {
        return switch (action) {
            case LEFT -> zh ? "左移" : "Move left";
            case RIGHT -> zh ? "右移" : "Move right";
            case ROTATE_CLOCKWISE -> zh ? "顺时针旋转" : "Rotate clockwise";
            case ROTATE_COUNTER_CLOCKWISE -> zh ? "逆时针旋转" : "Rotate counterclockwise";
            case SOFT_DROP -> zh ? "下移" : "Soft drop";
            case HARD_DROP -> zh ? "落子" : "Hard drop";
        };
    }

    private static Canvas targetPreview() {
        ShapeTarget target = ShapeTarget.HEART;
        Canvas canvas = new Canvas(target.width() * 14, target.height() * 14);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web("#edf1f7"));
        for (int row = 0; row < target.height(); row++) {
            for (int col = 0; col < target.width(); col++) {
                graphics.fillRoundRect(col * 14, row * 14, 12, 12, 2, 2);
            }
        }
        graphics.setFill(Color.web("#a59bc2"));
        target.requiredLocalCells().forEach(cell ->
                graphics.fillRoundRect(cell.col() * 14, cell.row() * 14, 12, 12, 2, 2));
        return canvas;
    }

    private static Label label(String style) {
        Label label = new Label();
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String style) {
        Button button = new Button();
        button.getStyleClass().add(style);
        return button;
    }
}
