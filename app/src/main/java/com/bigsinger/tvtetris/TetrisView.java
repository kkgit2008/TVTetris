package com.bigsinger.tvtetris;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@SuppressLint("ViewConstructor")
final class TetrisView extends View {
    private static final Typeface TYPEFACE_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface TYPEFACE_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final int[] BLOCK_COLORS = {
            Color.rgb(34, 211, 238),
            Color.rgb(250, 204, 21),
            Color.rgb(168, 85, 247),
            Color.rgb(52, 211, 153),
            Color.rgb(251, 113, 133),
            Color.rgb(59, 130, 246),
            Color.rgb(251, 146, 60)
    };

    private static final int BACKGROUND_TOP = Color.rgb(3, 5, 9);
    private static final int BACKGROUND_BOTTOM = Color.rgb(6, 10, 18);
    private static final long SAVE_INTERVAL_MS = 1800L;
    private static final long DOUBLE_DOWN_WINDOW_MS = 420L;

    private final GameEngine engine = new GameEngine();
    private final GameStorage storage;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF leftPanel = new RectF();
    private final RectF rightPanel = new RectF();
    private final RectF boardRect = new RectF();
    private final RectF centerHeader = new RectF();
    private final RectF scratchRect = new RectF();
    private final Handler handler = new Handler();
    private final Random effectsRandom = new Random();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final Matrix shaderMatrix = new Matrix();

    private List<Leaderboard.Entry> leaderboard;
    private ToneGenerator toneGenerator;
    private LinearGradient backgroundShader;
    private LinearGradient panelShader;
    private final HashMap<Integer, LinearGradient[]> blockShaderCache =
            new HashMap<Integer, LinearGradient[]>();
    private float cellSize;
    private float uiScale = 1f;
    private long nextDropAt;
    private long lastFrameAt;
    private long lastSaveAt;
    private long lastDownTapAt;
    private long clearFlashUntil;
    private int[] flashingRows = new int[0];
    private boolean restoredGame;
    private boolean frameLoopActive;

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!frameLoopActive) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            float elapsedSeconds = lastFrameAt == 0L
                    ? 0f
                    : Math.min(0.05f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;

            boolean changed = updateParticles(elapsedSeconds);
            if (engine.isRunning() && now >= nextDropAt) {
                handleStepResult(engine.stepDown(false));
                nextDropAt = now + engine.getDropIntervalMs();
                changed = true;
            }
            if (engine.isRunning() && now - lastSaveAt >= SAVE_INTERVAL_MS) {
                saveActiveGame();
            }
            if (now < clearFlashUntil) {
                changed = true;
            }
            if (changed || engine.isRunning()) {
                invalidate();
            }

            long delay = engine.isRunning() || !particles.isEmpty() || now < clearFlashUntil
                    ? 16L : 160L;
            handler.postDelayed(this, delay);
        }
    };

    TetrisView(Context context, GameStorage storage) {
        super(context);
        this.storage = storage;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
        setContentDescription("电视俄罗斯方块。使用方向键移动和旋转，双击下键直落，按确认键开始或暂停。");

        restoredGame = storage.restoreActiveGame(engine);
        leaderboard = storage.getLeaderboard();
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 45);
        } catch (RuntimeException ignored) {
            toneGenerator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        frameLoopActive = true;
        lastFrameAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(frameRunnable);
        handler.post(frameRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        frameLoopActive = false;
        handler.removeCallbacks(frameRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        backgroundShader = new LinearGradient(
                0f, 0f, 0f, height,
                BACKGROUND_TOP, BACKGROUND_BOTTOM,
                Shader.TileMode.CLAMP);
        panelShader = new LinearGradient(
                0f, 0f, width, height,
                new int[]{Color.rgb(12, 18, 30), Color.rgb(7, 11, 19), Color.rgb(10, 15, 25)},
                null,
                Shader.TileMode.CLAMP);
        blockShaderCache.clear();
        calculateLayout(width, height);
    }

    boolean handleRemoteKey(int keyCode, int repeatCount) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (repeatCount == 0) {
                    lastDownTapAt = 0L;
                    toggleGameState();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                lastDownTapAt = 0L;
                if (engine.moveHorizontal(-1)) {
                    afterPlayerMove();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                lastDownTapAt = 0L;
                if (engine.moveHorizontal(1)) {
                    afterPlayerMove();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                handleDownKey(repeatCount);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BUTTON_B:
                lastDownTapAt = 0L;
                if (repeatCount == 0 && engine.rotateClockwise()) {
                    playTone(ToneGenerator.TONE_PROP_BEEP, 35);
                    afterPlayerMove();
                }
                return true;
            default:
                return false;
        }
    }

    private void handleDownKey(int repeatCount) {
        if (!engine.isRunning()) {
            lastDownTapAt = 0L;
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean hardDrop = repeatCount == 0
                && lastDownTapAt > 0L
                && now - lastDownTapAt <= DOUBLE_DOWN_WINDOW_MS;
        GameEngine.StepResult result;
        if (hardDrop) {
            lastDownTapAt = 0L;
            playTone(ToneGenerator.TONE_PROP_BEEP, 55);
            result = engine.hardDrop();
        } else {
            if (repeatCount == 0) {
                lastDownTapAt = now;
            }
            result = engine.stepDown(true);
        }
        handleStepResult(result);
        nextDropAt = now + engine.getDropIntervalMs();
        saveActiveGame();
        invalidate();
    }

    void pauseForBackPress() {
        pauseAndSave();
    }

    void pauseForLifecycle() {
        pauseAndSave();
    }

    void saveBeforeExit() {
        saveActiveGame();
    }

    void release() {
        frameLoopActive = false;
        handler.removeCallbacks(frameRunnable);
        saveActiveGame();
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    private void toggleGameState() {
        lastDownTapAt = 0L;
        if (!engine.hasStarted() || engine.isGameOver()) {
            storage.clearActiveGame();
            particles.clear();
            flashingRows = new int[0];
            restoredGame = false;
            engine.newGame();
            nextDropAt = SystemClock.uptimeMillis() + engine.getDropIntervalMs();
            playTone(ToneGenerator.TONE_PROP_ACK, 90);
            announce("游戏开始");
            saveActiveGame();
        } else if (engine.isRunning()) {
            engine.setRunning(false);
            playTone(ToneGenerator.TONE_PROP_BEEP2, 70);
            announce("游戏已暂停");
            saveActiveGame();
        } else {
            restoredGame = false;
            engine.setRunning(true);
            nextDropAt = SystemClock.uptimeMillis() + engine.getDropIntervalMs();
            playTone(ToneGenerator.TONE_PROP_ACK, 70);
            announce("继续游戏");
        }
        invalidate();
    }

    private void pauseAndSave() {
        lastDownTapAt = 0L;
        if (engine.isRunning()) {
            engine.setRunning(false);
            playTone(ToneGenerator.TONE_PROP_BEEP2, 55);
        }
        saveActiveGame();
        invalidate();
    }

    private void afterPlayerMove() {
        saveActiveGame();
        invalidate();
    }

    private void handleStepResult(GameEngine.StepResult result) {
        if (result == null) {
            return;
        }
        if (result.locked) {
            lastDownTapAt = 0L;
        }
        if (result.clearedRows.length > 0) {
            flashingRows = result.clearedRows;
            clearFlashUntil = SystemClock.uptimeMillis() + 360L;
            createClearParticles(result.clearedRows);
            playTone(result.clearedRows.length == 4
                    ? ToneGenerator.TONE_PROP_ACK
                    : ToneGenerator.TONE_PROP_BEEP2, 130);
            storage.updateLeaderboard(engine);
            leaderboard = storage.getLeaderboard();
        }
        if (result.gameOver) {
            storage.clearActiveGame();
            storage.updateLeaderboard(engine);
            leaderboard = storage.getLeaderboard();
            playTone(ToneGenerator.TONE_PROP_NACK, 350);
            announce("游戏结束，得分 " + engine.getScore());
        } else if (result.locked) {
            saveActiveGame();
        }
    }

    private void saveActiveGame() {
        if (engine.hasActiveGame()) {
            storage.saveActiveGame(engine);
            lastSaveAt = SystemClock.uptimeMillis();
        }
    }

    private void playTone(int tone, int durationMs) {
        if (toneGenerator != null) {
            try {
                toneGenerator.startTone(tone, durationMs);
            } catch (RuntimeException ignored) {
                // Audio is decorative; a device without a tone stream can stay silent.
            }
        }
    }

    private void announce(String message) {
        if (message != null) {
            announceForAccessibility(message);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (boardRect.isEmpty()) {
            calculateLayout(getWidth(), getHeight());
        }
        drawBackground(canvas);
        drawPanel(canvas, leftPanel);
        drawPanel(canvas, rightPanel);
        drawCenterHeader(canvas);
        drawLeaderboard(canvas);
        drawBoard(canvas);
        drawNextPanel(canvas);
        drawParticles(canvas);
    }

    private void calculateLayout(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        uiScale = Math.max(0.72f, Math.min(1.45f, height / 900f));
        float margin = Math.max(14f, height * 0.026f);
        float gap = Math.max(14f, width * 0.016f);
        float headerHeight = Math.max(54f * uiScale, height * 0.074f);
        float availableBoardHeight = height - margin * 2f - headerHeight;
        cellSize = Math.min(availableBoardHeight / GameEngine.ROWS, width * 0.30f / GameEngine.COLUMNS);
        cellSize = Math.max(12f, cellSize);
        float boardWidth = cellSize * GameEngine.COLUMNS;
        float boardHeight = cellSize * GameEngine.ROWS;
        float boardLeft = (width - boardWidth) * 0.5f;
        float boardTop = margin + headerHeight;

        boardRect.set(boardLeft, boardTop, boardLeft + boardWidth, boardTop + boardHeight);
        centerHeader.set(boardLeft, margin, boardLeft + boardWidth, boardTop - 8f * uiScale);
        leftPanel.set(margin, margin, boardLeft - gap, height - margin);
        rightPanel.set(boardRect.right + gap, margin, width - margin, height - margin);
    }

    private void drawBackground(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(backgroundShader);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        paint.setShader(null);

        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(24, 80, 130, 190));
        float spacing = Math.max(70f, 92f * uiScale);
        for (float x = spacing * 0.5f; x < getWidth(); x += spacing) {
            canvas.drawLine(x, 0f, x, getHeight(), paint);
        }
        paint.setColor(Color.argb(13, 120, 170, 220));
        for (float y = spacing * 0.5f; y < getHeight(); y += spacing) {
            canvas.drawLine(0f, y, getWidth(), y, paint);
        }
    }

    private void drawPanel(Canvas canvas, RectF rect) {
        float radius = 18f * uiScale;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(panelShader);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.5f * uiScale));
        paint.setColor(Color.rgb(34, 49, 73));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCenterHeader(Canvas canvas) {
        drawPanel(canvas, centerHeader);
        float sectionWidth = centerHeader.width() / 3f;
        String[] labels = {"分数", "消行", "等级"};
        String[] values = {
                String.format(Locale.US, "%07d", engine.getScore()),
                String.format(Locale.US, "%03d", engine.getLines()),
                String.format(Locale.US, "%02d", engine.getLevel())
        };
        for (int index = 0; index < 3; index++) {
            float centerX = centerHeader.left + sectionWidth * (index + 0.5f);
            setTextStyle(13f * uiScale, Color.rgb(116, 137, 166), Paint.Align.CENTER, false);
            canvas.drawText(labels[index], centerX, centerHeader.top + 19f * uiScale, textPaint);
            setTextStyle(21f * uiScale, Color.rgb(229, 241, 255), Paint.Align.CENTER, true);
            canvas.drawText(values[index], centerX, centerHeader.bottom - 10f * uiScale, textPaint);
            if (index < 2) {
                paint.setColor(Color.rgb(35, 50, 73));
                paint.setStrokeWidth(1f);
                float separatorX = centerHeader.left + sectionWidth * (index + 1);
                canvas.drawLine(separatorX, centerHeader.top + 11f * uiScale,
                        separatorX, centerHeader.bottom - 11f * uiScale, paint);
            }
        }
    }

    private void drawLeaderboard(Canvas canvas) {
        float horizontalPadding = Math.max(16f, 24f * uiScale);
        float left = leftPanel.left + horizontalPadding;
        float right = leftPanel.right - horizontalPadding;
        float titleY = leftPanel.top + 38f * uiScale;

        setTextStyle(25f * uiScale, Color.rgb(91, 220, 255), Paint.Align.LEFT, true);
        canvas.drawText("TOP 20", left, titleY, textPaint);
        setTextStyle(13f * uiScale, Color.rgb(119, 139, 166), Paint.Align.RIGHT, false);
        canvas.drawText("本机最高积分", right, titleY - 2f * uiScale, textPaint);

        float lineY = leftPanel.top + 58f * uiScale;
        paint.setColor(Color.rgb(35, 53, 79));
        paint.setStrokeWidth(1f);
        canvas.drawLine(left, lineY, right, lineY, paint);

        float listTop = leftPanel.top + 69f * uiScale;
        float listBottom = leftPanel.bottom - 15f * uiScale;
        float rowHeight = (listBottom - listTop) / 20f;
        for (int index = 0; index < 20; index++) {
            float rowTop = listTop + index * rowHeight;
            float baseline = rowTop + rowHeight * 0.68f;
            boolean hasScore = index < leaderboard.size();
            Leaderboard.Entry entry = hasScore ? leaderboard.get(index) : null;
            boolean currentGameScore = entry != null
                    && engine.hasStarted()
                    && entry.gameId == engine.getGameId();

            if ((index < 3 || currentGameScore) && hasScore) {
                int highlight = currentGameScore
                        ? Color.argb(30, 168, 85, 247)
                        : (index == 0
                        ? Color.argb(24, 250, 204, 21)
                        : Color.argb(14, 91, 220, 255));
                paint.setColor(highlight);
                scratchRect.set(left - 7f * uiScale, rowTop + 1f,
                        right + 7f * uiScale, rowTop + rowHeight - 1f);
                canvas.drawRoundRect(scratchRect, 6f * uiScale, 6f * uiScale, paint);
            }

            int rankColor = currentGameScore
                    ? Color.rgb(192, 157, 255)
                    : (index == 0
                    ? Color.rgb(250, 204, 21)
                    : (index < 3 ? Color.rgb(99, 211, 255) : Color.rgb(98, 119, 148)));
            setTextStyle(Math.max(11f, 13f * uiScale), rankColor, Paint.Align.LEFT, true);
            canvas.drawText(String.format(Locale.US, "%02d", index + 1), left, baseline, textPaint);

            if (hasScore) {
                int scoreColor = currentGameScore
                        ? Color.rgb(210, 184, 255) : Color.rgb(224, 233, 247);
                setTextStyle(Math.max(12f, 15f * uiScale), scoreColor, Paint.Align.LEFT, true);
                canvas.drawText(String.format(Locale.US, "%,d", entry.score),
                        left + 42f * uiScale, baseline, textPaint);
                setTextStyle(Math.max(10f, 12f * uiScale), Color.rgb(112, 133, 160), Paint.Align.RIGHT, false);
                canvas.drawText(entry.formattedDate(), right, baseline, textPaint);
            } else {
                setTextStyle(Math.max(11f, 13f * uiScale), Color.rgb(49, 62, 82), Paint.Align.LEFT, false);
                canvas.drawText("—", left + 42f * uiScale, baseline, textPaint);
            }

            if (index < 19) {
                paint.setColor(Color.argb(46, 80, 101, 130));
                paint.setStrokeWidth(1f);
                canvas.drawLine(left, rowTop + rowHeight, right, rowTop + rowHeight, paint);
            }
        }
    }

    private void drawBoard(Canvas canvas) {
        float frame = Math.max(4f, 6f * uiScale);
        scratchRect.set(boardRect.left - frame, boardRect.top - frame,
                boardRect.right + frame, boardRect.bottom + frame);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(15, 26, 42));
        canvas.drawRoundRect(scratchRect, 8f * uiScale, 8f * uiScale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * uiScale);
        paint.setColor(Color.rgb(48, 76, 112));
        canvas.drawRoundRect(scratchRect, 8f * uiScale, 8f * uiScale, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(3, 6, 11));
        canvas.drawRect(boardRect, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(55, 72, 97, 128));
        for (int column = 1; column < GameEngine.COLUMNS; column++) {
            float x = boardRect.left + column * cellSize;
            canvas.drawLine(x, boardRect.top, x, boardRect.bottom, paint);
        }
        for (int row = 1; row < GameEngine.ROWS; row++) {
            float y = boardRect.top + row * cellSize;
            canvas.drawLine(boardRect.left, y, boardRect.right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        for (int row = 0; row < GameEngine.ROWS; row++) {
            for (int column = 0; column < GameEngine.COLUMNS; column++) {
                int value = engine.getCell(row, column);
                if (value > 0) {
                    drawBlock(canvas,
                            boardRect.left + column * cellSize,
                            boardRect.top + row * cellSize,
                            cellSize,
                            value - 1,
                            255);
                }
            }
        }

        if (engine.hasStarted() && !engine.isGameOver()) {
            drawGhostPiece(canvas);
            drawCurrentPiece(canvas);
        }
        drawClearFlash(canvas);
        drawGameOverlay(canvas);
    }

    private void drawGhostPiece(Canvas canvas) {
        int ghostY = engine.getGhostY();
        int[][] blocks = GameEngine.getBlocks(engine.getCurrentType(), engine.getRotation());
        int color = BLOCK_COLORS[engine.getCurrentType()];
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, cellSize * 0.055f));
        paint.setColor(Color.argb(engine.isRunning() ? 105 : 55,
                Color.red(color), Color.green(color), Color.blue(color)));
        float inset = cellSize * 0.16f;
        for (int[] block : blocks) {
            int boardY = ghostY + block[1];
            if (boardY < 0) {
                continue;
            }
            float left = boardRect.left + (engine.getPieceX() + block[0]) * cellSize + inset;
            float top = boardRect.top + boardY * cellSize + inset;
            scratchRect.set(left, top, left + cellSize - inset * 2f, top + cellSize - inset * 2f);
            canvas.drawRoundRect(scratchRect, cellSize * 0.1f, cellSize * 0.1f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCurrentPiece(Canvas canvas) {
        int[][] blocks = GameEngine.getBlocks(engine.getCurrentType(), engine.getRotation());
        int alpha = engine.isRunning() ? 255 : 165;
        for (int[] block : blocks) {
            int boardX = engine.getPieceX() + block[0];
            int boardY = engine.getPieceY() + block[1];
            if (boardY >= 0) {
                drawBlock(canvas,
                        boardRect.left + boardX * cellSize,
                        boardRect.top + boardY * cellSize,
                        cellSize,
                        engine.getCurrentType(),
                        alpha);
            }
        }
    }

    private void drawBlock(Canvas canvas, float x, float y, float size, int type, int alpha) {
        LinearGradient[] shaders = getBlockShaders(size);
        float inset = Math.max(1f, size * 0.055f);
        float radius = Math.max(2f, size * 0.15f);
        scratchRect.set(x + inset, y + inset, x + size - inset, y + size - inset);

        shaderMatrix.reset();
        shaderMatrix.setTranslate(x, y);
        shaders[type].setLocalMatrix(shaderMatrix);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shaders[type]);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(scratchRect, radius, radius, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        int base = BLOCK_COLORS[type];
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, size * 0.045f));
        paint.setColor(Color.argb(alpha * 150 / 255, 238, 251, 255));
        scratchRect.inset(size * 0.085f, size * 0.085f);
        canvas.drawRoundRect(scratchRect, radius * 0.65f, radius * 0.65f, paint);

        paint.setStrokeWidth(Math.max(1f, size * 0.035f));
        paint.setColor(Color.argb(alpha * 100 / 255, 255, 255, 255));
        float edge = size * 0.17f;
        canvas.drawLine(x + edge, y + edge, x + size - edge, y + edge, paint);
        canvas.drawLine(x + edge, y + edge, x + edge, y + size * 0.58f, paint);
        paint.setColor(Color.argb(alpha * 100 / 255,
                Color.red(darken(base, 0.55f)), Color.green(darken(base, 0.55f)), Color.blue(darken(base, 0.55f))));
        canvas.drawLine(x + edge, y + size - edge, x + size - edge, y + size - edge, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private LinearGradient[] getBlockShaders(float size) {
        int cacheKey = Math.max(1, Math.round(size * 10f));
        LinearGradient[] shaders = blockShaderCache.get(cacheKey);
        if (shaders != null) {
            return shaders;
        }
        shaders = new LinearGradient[BLOCK_COLORS.length];
        for (int type = 0; type < BLOCK_COLORS.length; type++) {
            int base = BLOCK_COLORS[type];
            shaders[type] = new LinearGradient(
                    0f, 0f, size, size,
                    new int[]{lighten(base, 1.42f), base, darken(base, 0.48f)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
        }
        blockShaderCache.put(cacheKey, shaders);
        return shaders;
    }

    private void drawClearFlash(Canvas canvas) {
        long remaining = clearFlashUntil - SystemClock.uptimeMillis();
        if (remaining <= 0L || flashingRows.length == 0) {
            return;
        }
        float progress = 1f - remaining / 360f;
        int alpha = (int) (170f * (1f - progress));
        for (int row : flashingRows) {
            float top = boardRect.top + row * cellSize;
            paint.setShader(new LinearGradient(
                    boardRect.left, top, boardRect.right, top,
                    new int[]{Color.TRANSPARENT, Color.argb(alpha, 225, 251, 255), Color.TRANSPARENT},
                    null,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(boardRect.left, top, boardRect.right, top + cellSize, paint);
        }
        paint.setShader(null);
    }

    private void drawGameOverlay(Canvas canvas) {
        if (engine.isRunning()) {
            return;
        }
        paint.setColor(Color.argb(190, 2, 5, 10));
        canvas.drawRect(boardRect, paint);

        float centerX = boardRect.centerX();
        float centerY = boardRect.centerY();
        String title;
        String subtitle;
        int accent;
        if (engine.isGameOver()) {
            title = "GAME OVER";
            subtitle = "本局 " + String.format(Locale.US, "%,d", engine.getScore()) + " 分  ·  按 OK 开新局";
            accent = Color.rgb(251, 113, 133);
        } else if (engine.hasStarted()) {
            title = restoredGame ? "已恢复上次对局" : "游戏已暂停";
            subtitle = "按 OK 继续";
            accent = Color.rgb(250, 204, 21);
        } else {
            title = "TV TETRIS";
            subtitle = "按 OK 开始";
            accent = Color.rgb(91, 220, 255);
        }

        float cardWidth = boardRect.width() * 0.86f;
        float cardHeight = 140f * uiScale;
        scratchRect.set(centerX - cardWidth * 0.5f, centerY - cardHeight * 0.5f,
                centerX + cardWidth * 0.5f, centerY + cardHeight * 0.5f);
        paint.setColor(Color.argb(225, 12, 19, 31));
        canvas.drawRoundRect(scratchRect, 14f * uiScale, 14f * uiScale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * uiScale);
        paint.setColor(accent);
        canvas.drawRoundRect(scratchRect, 14f * uiScale, 14f * uiScale, paint);
        paint.setStyle(Paint.Style.FILL);

        setTextStyle(27f * uiScale, accent, Paint.Align.CENTER, true);
        canvas.drawText(title, centerX, centerY - 10f * uiScale, textPaint);
        setTextStyle(14f * uiScale, Color.rgb(210, 222, 238), Paint.Align.CENTER, false);
        canvas.drawText(subtitle, centerX, centerY + 28f * uiScale, textPaint);
    }

    private void drawNextPanel(Canvas canvas) {
        float padding = Math.max(16f, 24f * uiScale);
        float left = rightPanel.left + padding;
        float right = rightPanel.right - padding;
        float titleY = rightPanel.top + 38f * uiScale;
        setTextStyle(25f * uiScale, Color.rgb(192, 157, 255), Paint.Align.LEFT, true);
        canvas.drawText("接下来", left, titleY, textPaint);

        int[] next = engine.hasStarted() && !engine.isGameOver()
                ? engine.getNextPieces(2) : new int[]{GameEngine.T, GameEngine.I};
        float cardTop = rightPanel.top + 62f * uiScale;
        float previewHeight = Math.min(174f * uiScale, rightPanel.height() * 0.205f);
        float cardGap = 13f * uiScale;
        for (int index = 0; index < 2; index++) {
            scratchRect.set(left, cardTop + index * (previewHeight + cardGap),
                    right, cardTop + index * (previewHeight + cardGap) + previewHeight);
            drawPreviewCard(canvas, scratchRect, index == 0 ? "NEXT" : "AFTER", next[index], index == 0);
        }

        float controlsTop = cardTop + 2f * (previewHeight + cardGap) + 10f * uiScale;
        drawControls(canvas, left, right, controlsTop);
        drawStatusCard(canvas, left, right);
    }

    private void drawPreviewCard(Canvas canvas, RectF card, String label, int type, boolean primary) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primary ? Color.rgb(14, 23, 38) : Color.rgb(10, 17, 29));
        canvas.drawRoundRect(card, 13f * uiScale, 13f * uiScale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(primary ? 1.5f * uiScale : 1f);
        paint.setColor(primary ? Color.rgb(58, 83, 121) : Color.rgb(34, 50, 74));
        canvas.drawRoundRect(card, 13f * uiScale, 13f * uiScale, paint);
        paint.setStyle(Paint.Style.FILL);

        setTextStyle(11f * uiScale, Color.rgb(101, 124, 155), Paint.Align.LEFT, true);
        canvas.drawText(label, card.left + 14f * uiScale, card.top + 22f * uiScale, textPaint);

        int[][] blocks = GameEngine.getBlocks(type, 0);
        int minX = 4;
        int maxX = 0;
        int minY = 4;
        int maxY = 0;
        for (int[] block : blocks) {
            minX = Math.min(minX, block[0]);
            maxX = Math.max(maxX, block[0]);
            minY = Math.min(minY, block[1]);
            maxY = Math.max(maxY, block[1]);
        }
        int pieceColumns = maxX - minX + 1;
        int pieceRows = maxY - minY + 1;
        float blockSize = Math.min(
                card.width() * 0.62f / Math.max(4, pieceColumns),
                (card.height() - 37f * uiScale) * 0.72f / Math.max(2, pieceRows));
        blockSize = Math.max(12f, blockSize);
        float pieceWidth = pieceColumns * blockSize;
        float pieceHeight = pieceRows * blockSize;
        float originX = card.centerX() - pieceWidth * 0.5f - minX * blockSize;
        float originY = card.top + 35f * uiScale
                + (card.height() - 35f * uiScale - pieceHeight) * 0.5f
                - minY * blockSize;
        for (int[] block : blocks) {
            drawBlock(canvas, originX + block[0] * blockSize, originY + block[1] * blockSize,
                    blockSize, type, primary ? 255 : 205);
        }
    }

    private void drawControls(Canvas canvas, float left, float right, float top) {
        setTextStyle(16f * uiScale, Color.rgb(213, 224, 240), Paint.Align.LEFT, true);
        canvas.drawText("遥控器操作", left, top + 20f * uiScale, textPaint);

        float dpadCenterX = left + Math.min(76f * uiScale, (right - left) * 0.22f);
        float dpadCenterY = top + 96f * uiScale;
        float keySize = 32f * uiScale;
        drawKeyCap(canvas, dpadCenterX, dpadCenterY - keySize, "↑");
        drawKeyCap(canvas, dpadCenterX - keySize, dpadCenterY, "←");
        drawKeyCap(canvas, dpadCenterX, dpadCenterY, "↓");
        drawKeyCap(canvas, dpadCenterX + keySize, dpadCenterY, "→");

        float descriptionX = left + Math.min(170f * uiScale, (right - left) * 0.48f);
        setTextStyle(13f * uiScale, Color.rgb(141, 160, 185), Paint.Align.LEFT, false);
        canvas.drawText("↑ 旋转", descriptionX, top + 67f * uiScale, textPaint);
        canvas.drawText("← → 移动", descriptionX, top + 92f * uiScale, textPaint);
        canvas.drawText("↓ 软降 / 双击直落", descriptionX, top + 117f * uiScale, textPaint);

        float okTop = top + 148f * uiScale;
        scratchRect.set(left, okTop, left + 72f * uiScale, okTop + 34f * uiScale);
        paint.setColor(Color.rgb(33, 89, 114));
        canvas.drawRoundRect(scratchRect, 17f * uiScale, 17f * uiScale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * uiScale);
        paint.setColor(Color.rgb(91, 220, 255));
        canvas.drawRoundRect(scratchRect, 17f * uiScale, 17f * uiScale, paint);
        paint.setStyle(Paint.Style.FILL);
        setTextStyle(14f * uiScale, Color.WHITE, Paint.Align.CENTER, true);
        canvas.drawText("OK", scratchRect.centerX(), okTop + 23f * uiScale, textPaint);
        setTextStyle(13f * uiScale, Color.rgb(141, 160, 185), Paint.Align.LEFT, false);
        canvas.drawText("开始 / 暂停 / 继续", left + 88f * uiScale, okTop + 23f * uiScale, textPaint);
    }

    private void drawKeyCap(Canvas canvas, float centerX, float centerY, String symbol) {
        float radius = 14f * uiScale;
        paint.setColor(Color.rgb(24, 35, 52));
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * uiScale);
        paint.setColor(Color.rgb(67, 91, 122));
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        setTextStyle(15f * uiScale, Color.rgb(208, 225, 245), Paint.Align.CENTER, true);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText(symbol, centerX, baseline, textPaint);
    }

    private void drawStatusCard(Canvas canvas, float left, float right) {
        float height = 55f * uiScale;
        float bottom = rightPanel.bottom - 18f * uiScale;
        scratchRect.set(left, bottom - height, right, bottom);
        paint.setColor(Color.rgb(10, 17, 28));
        canvas.drawRoundRect(scratchRect, 11f * uiScale, 11f * uiScale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Color.rgb(35, 51, 74));
        canvas.drawRoundRect(scratchRect, 11f * uiScale, 11f * uiScale, paint);
        paint.setStyle(Paint.Style.FILL);

        String status;
        int color;
        if (engine.isRunning()) {
            status = "进行中";
            color = Color.rgb(52, 211, 153);
        } else if (engine.isGameOver()) {
            status = "本局结束";
            color = Color.rgb(251, 113, 133);
        } else if (engine.hasStarted()) {
            status = restoredGame ? "已恢复 · 等待继续" : "已暂停";
            color = Color.rgb(250, 204, 21);
        } else {
            status = "等待开始";
            color = Color.rgb(91, 220, 255);
        }
        paint.setColor(color);
        canvas.drawCircle(left + 18f * uiScale, scratchRect.centerY(), 5f * uiScale, paint);
        setTextStyle(13f * uiScale, Color.rgb(203, 216, 233), Paint.Align.LEFT, true);
        canvas.drawText(status, left + 33f * uiScale,
                scratchRect.centerY() + 5f * uiScale, textPaint);
    }

    private void createClearParticles(int[] rows) {
        if (boardRect.isEmpty()) {
            return;
        }
        for (int row : rows) {
            for (int column = 0; column < GameEngine.COLUMNS; column++) {
                int color = BLOCK_COLORS[(column + row) % BLOCK_COLORS.length];
                for (int spark = 0; spark < 4; spark++) {
                    float x = boardRect.left + (column + 0.5f) * cellSize;
                    float y = boardRect.top + (row + 0.5f) * cellSize;
                    float velocityX = (effectsRandom.nextFloat() - 0.5f) * cellSize * 5.5f;
                    float velocityY = (-1.2f - effectsRandom.nextFloat() * 3.2f) * cellSize;
                    float life = 0.35f + effectsRandom.nextFloat() * 0.35f;
                    particles.add(new Particle(x, y, velocityX, velocityY, life,
                            color, cellSize * (0.045f + effectsRandom.nextFloat() * 0.09f)));
                }
            }
        }
    }

    private boolean updateParticles(float elapsedSeconds) {
        if (particles.isEmpty() || elapsedSeconds <= 0f) {
            return false;
        }
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle particle = iterator.next();
            particle.life -= elapsedSeconds;
            if (particle.life <= 0f) {
                iterator.remove();
                continue;
            }
            particle.x += particle.velocityX * elapsedSeconds;
            particle.y += particle.velocityY * elapsedSeconds;
            particle.velocityY += cellSize * 7.5f * elapsedSeconds;
        }
        return true;
    }

    private void drawParticles(Canvas canvas) {
        for (Particle particle : particles) {
            float fraction = Math.min(1f, particle.life / particle.maxLife);
            int alpha = (int) (255f * fraction);
            int color = particle.color;
            paint.setShader(new RadialGradient(
                    particle.x, particle.y, Math.max(1f, particle.size * 2.4f),
                    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(particle.x, particle.y, particle.size * 2.4f, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(alpha, 235, 252, 255));
            canvas.drawCircle(particle.x, particle.y, particle.size, paint);
        }
    }

    private void setTextStyle(float size, int color, Paint.Align align, boolean medium) {
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(align);
        textPaint.setTypeface(medium ? TYPEFACE_MEDIUM : TYPEFACE_REGULAR);
    }

    private static int lighten(int color, float factor) {
        return Color.rgb(
                clampColor(Color.red(color) * factor),
                clampColor(Color.green(color) * factor),
                clampColor(Color.blue(color) * factor));
    }

    private static int darken(int color, float factor) {
        return lighten(color, factor);
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static final class Particle {
        float x;
        float y;
        float velocityX;
        float velocityY;
        float life;
        final float maxLife;
        final int color;
        final float size;

        Particle(float x, float y, float velocityX, float velocityY,
                 float life, int color, float size) {
            this.x = x;
            this.y = y;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.life = life;
            this.maxLife = life;
            this.color = color;
            this.size = size;
        }
    }
}
