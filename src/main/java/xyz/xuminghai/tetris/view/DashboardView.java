package xyz.xuminghai.tetris.view;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
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
    private final Label actionsTitle = label("section-heading");
    private final Label keys = label("keyboard-note");
    private final Label shapeProgress = label("shape-progress");
    private TitledPane creativePane;
    private final Button manualButton = button("mode-button");
    private final Button aiButton = button("mode-button");
    private final Button startButton = button("primary-button");
    private final Button takeoverButton = button("takeover-button");
    private final Button languageButton = button("quiet-button");
    private final VBox actions = new VBox(3);
    private final ScrollPane actionScroll = new ScrollPane(actions);
    private final VBox overlayContent = new VBox(6, overlayTitle, overlayMessage);
    private final StackPane overlay = new StackPane(overlayContent);
    private List<AiAction> shownPlan = List.of();
    private List<ActionGroup> actionGroups = List.of();
    private final List<HBox> actionRows = new ArrayList<>();
    private final List<Label> actionBadges = new ArrayList<>();
    private final List<Label> actionTexts = new ArrayList<>();
    private boolean everStarted;
    private Boolean chineseFont;

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
        this.objective = configuredObjective;
        this.boardCellSide = boardCellSide;
        this.viewportWidth = viewportWidth;
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
        world.settledCellsProperty().addListener((_, _, _) -> refresh());
        world.languageProperty().addListener((_, _, _) -> refresh());
        refresh();
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
        });
        aiButton.setOnAction(_ -> {
            if (!world.aiEnabledProperty().get()) world.toggleAi();
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
        StackPane boardWell = new StackPane(board, ghost, fallingPieceView, sweepLayer, rowShiftView, overlay);
        boardWell.getStyleClass().add("board-frame");
        boardWell.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Circle stageDot = new Circle(3);
        stageDot.getStyleClass().add("status-dot");
        HBox boardHead = new HBox(6, stageDot, stageStatus);
        boardHead.getStyleClass().add("stage-head");
        boardHead.setMaxWidth(board.getWidth() + 22);
        startButton.setOnAction(_ -> world.startOrPauseGame());
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
        Label badge = label("agent-badge");
        badge.setText(agent.toUpperCase(Locale.ROOT));
        HBox heading = new HBox(agentTitle, new Region(), badge);
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
        double actionHeight = boardCellSide < 20 ? 105 : 140;
        actionScroll.setPrefViewportHeight(actionHeight);
        actionScroll.setMaxHeight(actionHeight);
        actionScroll.getStyleClass().add("action-scroll");
        VBox actionSection = new VBox(12, actionsTitle, actionScroll);
        actionSection.getStyleClass().add("cockpit-section");

        takeoverButton.setOnAction(_ -> world.toggleAi());
        takeoverButton.setMaxWidth(Double.MAX_VALUE);
        Circle statusDot = new Circle(3);
        statusDot.getStyleClass().add("status-dot");
        HBox status = new HBox(7, statusDot, aiStatus);
        status.setAlignment(Pos.CENTER_LEFT);
        aiStatus.setWrapText(true);
        VBox body = new VBox(12, status, aiMessage, goalSection,
                actionSection, takeoverButton);
        body.getStyleClass().add("cockpit-body");
        aiMessage.setWrapText(true);
        VBox card = new VBox(heading, body);
        card.getStyleClass().add("cockpit-card");
        if (objective.equals("build-shape")) {
            creativePane = new TitledPane();
            creativePane.setExpanded(false);
            creativePane.getStyleClass().add("creative-pane");
            Canvas target = targetPreview();
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
        minus.setTooltip(new Tooltip("Level −"));
        minus.setOnAction(_ -> world.levelMinus());
        Button plus = button("stepper-button");
        plus.setText("+");
        plus.setTooltip(new Tooltip("Level +"));
        plus.setOnAction(_ -> world.levelPlus());
        HBox levelControls = new HBox(8, minus, plus);
        levelControls.getStyleClass().add("level-controls");
        HBox auxiliary = new HBox(10, shortcuts, levelControls);
        auxiliary.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(shortcuts, Priority.ALWAYS);
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

    private void refresh() {
        boolean zh = world.languageProperty().get().getLanguage().equals("zh");
        useSystemFont(zh);
        boolean enabled = world.aiEnabledProperty().get();
        boolean active = world.activeDisplayProperty().get();
        boolean over = world.gameOverDisplayProperty().get();
        GameWorld.AiDisplay display = world.aiDisplayProperty().get();

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
        actionsTitle.setText(zh ? "本次动作" : "Current plan");
        languageButton.setText(zh ? "EN" : "中文");
        manualButton.setText(zh ? "手动" : "Manual");
        aiButton.setText(agent);
        manualButton.pseudoClassStateChanged(SELECTED, !enabled);
        aiButton.pseudoClassStateChanged(SELECTED, enabled);
        controller.setText(enabled ? agent : (zh ? "手动控制" : "Manual"));
        goal.setText(switch (objective) {
            case "build-shape" -> zh ? "心形构造 · 生存优先" : "Build a heart · survival first";
            case "tuck-hunter" -> zh ? "凹槽探索 · 生存优先" : "Tuck hunting · survival first";
            default -> zh ? "优先生存，持续消行" : "Survive and clear lines";
        });
        if (creativePane != null) {
            creativePane.setText(zh ? "创意目标 · HEART" : "Creative goal · HEART");
            boolean[][] occupied = new boolean[world.getRows()][world.getCols()];
            world.settledCellsProperty().get().forEach(cell ->
                    occupied[cell.row()][cell.col()] = true);
            ShapeProgress progress = ShapeTarget.HEART.progress(occupied);
            shapeProgress.setText(zh
                    ? "目标覆盖 " + progress.matchedRequiredCells() + "/" + progress.requiredCells()
                            + " · 背景侵入 " + progress.forbiddenOccupiedCells()
                    : "Coverage " + progress.matchedRequiredCells() + "/" + progress.requiredCells()
                            + " · Intrusions " + progress.forbiddenOccupiedCells());
        }
        startButton.setText(active ? (zh ? "暂停" : "Pause")
                : over ? (zh ? "重新开始" : "Restart")
                : everStarted ? (zh ? "继续" : "Resume") : (zh ? "开始游戏" : "Start game"));
        takeoverButton.setText(enabled ? (zh ? "接管游戏 · 手动" : "Take over · manual")
                : (zh ? "交给已配置 AI" : "Enable configured AI"));

        overlay.setVisible(!active);
        overlayTitle.setText(over ? (zh ? "游戏结束" : "Game over")
                : everStarted ? (zh ? "已暂停" : "Paused")
                : (zh ? "准备开始" : "Ready to play"));
        overlayMessage.setText(over ? (zh ? "重新开始一局新的对局" : "Start a new game when ready")
                : everStarted ? (zh ? "对局与 AI 执行已暂停" : "The game and AI execution are paused")
                : (zh ? "选择控制方式，开始对局" : "Choose a controller and start playing"));
        stageStatus.setText(over ? (zh ? "游戏结束" : "Game over")
                : active ? (zh ? "对局进行中" : "Game in progress")
                : everStarted ? (zh ? "已暂停" : "Paused")
                : (zh ? "准备就绪" : "Ready"));

        String phase = display.phase();
        aiStatus.setText(over ? (zh ? "对局结束" : "Game over") : switch (phase) {
            case "thinking" -> zh ? "正在选择落点" : "Choosing a placement";
            case "next-piece" -> zh ? "下一块起生效" : "Starts with next piece";
            case "executing" -> zh ? "正在执行" : "Executing";
            case "fallback" -> zh ? "已切换本地策略" : "Local fallback used";
            case "failed" -> zh ? "决策失败" : "Decision failed";
            case "completed" -> zh ? "本步完成" : "Plan completed";
            case "paused" -> zh ? "AI 已暂停" : "AI paused";
            default -> enabled ? (zh ? "等待决策" : "Ready")
                    : (zh ? "手动控制" : "Manual control");
        });
        aiMessage.setText(!enabled ? (zh ? "AI 未执行；键盘操作优先。" : "AI is idle. Keyboard input has priority.")
                : phase.equals("paused") ? (zh ? "继续对局后按最新状态重新决策。" : "Resume to decide from the latest state.")
                : phase.equals("thinking") ? (zh ? "等待当前方块的决策结果。" : "Waiting for this piece's decision.")
                : phase.equals("next-piece") ? (zh ? "当前方块仍可手动操作。" : "The current piece remains under manual control.")
                : phase.equals("fallback") ? (zh ? "本步使用本地启发式策略。" : "This move used the local heuristic.")
                : phase.equals("failed") ? (zh ? "本次决策未执行；可继续手动操作。" : "Decision did not execute; manual input is available.")
                : (zh ? "按真实游戏状态执行动作。" : "Actions follow the live game state."));
        keys.setText(zh
                ? "A / D  左右移动   ·   S  下移\n← / →  旋转   ·   Space  开始或暂停\nF2  AI 开关   ·   + / −  调整等级\nCtrl + Tab  切换语言"
                : "A / D  Move   ·   S  Soft drop\n← / →  Rotate   ·   Space  Start or pause\nF2  Toggle AI   ·   + / −  Level\nCtrl + Tab  Language");
        reducedMotion.setText(zh ? "减少界面动效" : "Reduce UI motion");
        boardHint.setText(zh ? "空心轮廓 · 预计落点" : "Outline · projected landing");
        refreshActions(display, zh);
    }

    private void refreshActions(GameWorld.AiDisplay display, boolean zh) {
        List<AiAction> plan = display.actions();
        int completed = display.completedActions();
        if (!shownPlan.equals(plan) || actions.getChildren().isEmpty()) {
            shownPlan = List.copyOf(plan);
            actionGroups = groupActions(plan);
            actions.getChildren().clear();
            actionRows.clear();
            actionBadges.clear();
            actionTexts.clear();
            for (int index = 0; index < actionGroups.size(); index++) {
                Label badge = label("action-number");
                Label text = label("action-text");
                text.setWrapText(true);
                HBox row = new HBox(12, badge, text);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("action-row");
                HBox.setHgrow(text, Priority.ALWAYS);
                actionRows.add(row);
                actionBadges.add(badge);
                actionTexts.add(text);
                actions.getChildren().add(row);
            }
            if (plan.isEmpty()) actions.getChildren().add(label("empty-actions"));
        }
        double maximumHeight = boardCellSide < 20 ? 90 : getStyleClass().contains("compact-height") ? 120 : 140;
        double viewportHeight = plan.isEmpty() ? 36 : Math.min(maximumHeight, actionGroups.size() * 44.0);
        actionScroll.setPrefViewportHeight(viewportHeight);
        actionScroll.setMaxHeight(viewportHeight);
        if (plan.isEmpty()) {
            Label empty = (Label) actions.getChildren().getFirst();
            empty.setText(zh ? "当前没有执行中的计划" : "No active plan");
            actionScroll.setVvalue(0);
            return;
        }
        int activeIndex = 0;
        for (int index = 0; index < actionGroups.size(); index++) {
            ActionGroup group = actionGroups.get(index);
            int count = group.end() - group.start();
            boolean done = completed >= group.end();
            boolean active = !done && completed >= group.start()
                    && display.phase().equals("executing");
            HBox row = actionRows.get(index);
            row.pseudoClassStateChanged(PseudoClass.getPseudoClass("done"), done);
            row.pseudoClassStateChanged(PseudoClass.getPseudoClass("active"), active);
            actionBadges.get(index).setText(done ? "✓" : "%02d".formatted(index + 1));
            String text = actionName(group.action(), zh) + (count > 1 ? " × " + count : "");
            if (active && count > 1) text += " · " + (completed - group.start()) + "/" + count;
            actionTexts.get(index).setText(text);
            if (active || done) activeIndex = index;
        }
        actionScroll.setVvalue(Math.clamp((activeIndex - 1.0) / Math.max(1, actionGroups.size() - 3), 0, 1));
    }

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
        if (chineseFont != null && chineseFont == chinese) return;
        chineseFont = chinese;
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
