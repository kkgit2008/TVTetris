package com.bigsinger.tvtetris;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pure Java game rules and state. Rendering and Android lifecycle code live elsewhere. */
final class GameEngine {
    static final int COLUMNS = 10;
    static final int ROWS = 20;
    static final int PIECE_COUNT = 7;

    static final int I = 0;
    static final int O = 1;
    static final int T = 2;
    static final int S = 3;
    static final int Z = 4;
    static final int J = 5;
    static final int L = 6;

    private static final int[][][][] SHAPES = {
            {
                    {{0, 1}, {1, 1}, {2, 1}, {3, 1}},
                    {{2, 0}, {2, 1}, {2, 2}, {2, 3}},
                    {{0, 2}, {1, 2}, {2, 2}, {3, 2}},
                    {{1, 0}, {1, 1}, {1, 2}, {1, 3}}
            },
            {
                    {{1, 0}, {2, 0}, {1, 1}, {2, 1}},
                    {{1, 0}, {2, 0}, {1, 1}, {2, 1}},
                    {{1, 0}, {2, 0}, {1, 1}, {2, 1}},
                    {{1, 0}, {2, 0}, {1, 1}, {2, 1}}
            },
            {
                    {{1, 0}, {0, 1}, {1, 1}, {2, 1}},
                    {{1, 0}, {1, 1}, {2, 1}, {1, 2}},
                    {{0, 1}, {1, 1}, {2, 1}, {1, 2}},
                    {{1, 0}, {0, 1}, {1, 1}, {1, 2}}
            },
            {
                    {{1, 0}, {2, 0}, {0, 1}, {1, 1}},
                    {{1, 0}, {1, 1}, {2, 1}, {2, 2}},
                    {{1, 1}, {2, 1}, {0, 2}, {1, 2}},
                    {{0, 0}, {0, 1}, {1, 1}, {1, 2}}
            },
            {
                    {{0, 0}, {1, 0}, {1, 1}, {2, 1}},
                    {{2, 0}, {1, 1}, {2, 1}, {1, 2}},
                    {{0, 1}, {1, 1}, {1, 2}, {2, 2}},
                    {{1, 0}, {0, 1}, {1, 1}, {0, 2}}
            },
            {
                    {{0, 0}, {0, 1}, {1, 1}, {2, 1}},
                    {{1, 0}, {2, 0}, {1, 1}, {1, 2}},
                    {{0, 1}, {1, 1}, {2, 1}, {2, 2}},
                    {{1, 0}, {1, 1}, {0, 2}, {1, 2}}
            },
            {
                    {{2, 0}, {0, 1}, {1, 1}, {2, 1}},
                    {{1, 0}, {1, 1}, {1, 2}, {2, 2}},
                    {{0, 1}, {1, 1}, {2, 1}, {0, 2}},
                    {{0, 0}, {1, 0}, {1, 1}, {1, 2}}
            }
    };

    private final int[][] board = new int[ROWS][COLUMNS];
    private final ArrayDeque<Integer> nextQueue = new ArrayDeque<Integer>();
    private final Random random = new Random();

    private int currentType;
    private int rotation;
    private int pieceX;
    private int pieceY;
    private int score;
    private int lines;
    private long gameId;
    private long startedAt;
    private boolean started;
    private boolean running;
    private boolean gameOver;

    void newGame() {
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                board[row][column] = 0;
            }
        }
        nextQueue.clear();
        score = 0;
        lines = 0;
        startedAt = System.currentTimeMillis();
        gameId = createGameId();
        started = true;
        running = true;
        gameOver = false;
        ensureQueue();
        spawnNextPiece();
    }

    boolean restore(int[][] savedBoard, int type, int savedRotation, int x, int y,
                    int savedScore, int savedLines, int[] savedQueue) {
        return restore(savedBoard, type, savedRotation, x, y, savedScore, savedLines,
                savedQueue, 0L, 0L);
    }

    boolean restore(int[][] savedBoard, int type, int savedRotation, int x, int y,
                    int savedScore, int savedLines, int[] savedQueue,
                    long savedGameId, long savedStartedAt) {
        if (savedBoard == null || savedBoard.length != ROWS || type < 0 || type >= PIECE_COUNT) {
            return false;
        }
        for (int row = 0; row < ROWS; row++) {
            if (savedBoard[row] == null || savedBoard[row].length != COLUMNS) {
                return false;
            }
            for (int column = 0; column < COLUMNS; column++) {
                int value = savedBoard[row][column];
                if (value < 0 || value > PIECE_COUNT) {
                    return false;
                }
                board[row][column] = value;
            }
        }

        currentType = type;
        rotation = normalizeRotation(savedRotation);
        pieceX = x;
        pieceY = y;
        score = Math.max(0, savedScore);
        lines = Math.max(0, savedLines);
        startedAt = savedStartedAt > 0L ? savedStartedAt : System.currentTimeMillis();
        gameId = savedGameId > 0L ? savedGameId : createGameId();
        nextQueue.clear();
        if (savedQueue != null) {
            for (int piece : savedQueue) {
                if (piece >= 0 && piece < PIECE_COUNT) {
                    nextQueue.addLast(piece);
                }
            }
        }
        ensureQueue();

        if (!canPlace(currentType, rotation, pieceX, pieceY)) {
            return false;
        }
        started = true;
        running = false;
        gameOver = false;
        return true;
    }

    boolean moveHorizontal(int direction) {
        if (!running || gameOver || (direction != -1 && direction != 1)) {
            return false;
        }
        if (canPlace(currentType, rotation, pieceX + direction, pieceY)) {
            pieceX += direction;
            return true;
        }
        return false;
    }

    boolean rotateClockwise() {
        if (!running || gameOver) {
            return false;
        }
        int nextRotation = normalizeRotation(rotation + 1);
        int[][] kicks = {{0, 0}, {-1, 0}, {1, 0}, {-2, 0}, {2, 0}, {0, -1}};
        for (int[] kick : kicks) {
            if (canPlace(currentType, nextRotation, pieceX + kick[0], pieceY + kick[1])) {
                rotation = nextRotation;
                pieceX += kick[0];
                pieceY += kick[1];
                return true;
            }
        }
        return false;
    }

    StepResult stepDown(boolean manual) {
        if (!running || gameOver) {
            return StepResult.NONE;
        }
        if (canPlace(currentType, rotation, pieceX, pieceY + 1)) {
            pieceY++;
            if (manual) {
                score++;
            }
            return StepResult.MOVED;
        }
        return lockCurrentPiece();
    }

    StepResult hardDrop() {
        if (!running || gameOver) {
            return StepResult.NONE;
        }
        int distance = 0;
        while (canPlace(currentType, rotation, pieceX, pieceY + 1)) {
            pieceY++;
            distance++;
        }
        score += distance * 2;
        return lockCurrentPiece();
    }

    private long createGameId() {
        long candidate = random.nextLong() & Long.MAX_VALUE;
        if (candidate == 0L) {
            candidate = Math.max(1L, System.currentTimeMillis());
        }
        return candidate;
    }

    private StepResult lockCurrentPiece() {
        boolean aboveBoard = false;
        int[][] blocks = getBlocks(currentType, rotation);
        for (int[] block : blocks) {
            int x = pieceX + block[0];
            int y = pieceY + block[1];
            if (y < 0) {
                aboveBoard = true;
            } else if (y < ROWS && x >= 0 && x < COLUMNS) {
                board[y][x] = currentType + 1;
            }
        }

        int[] fullRows = findFullRows();
        if (fullRows.length > 0) {
            collapseRows(fullRows);
            int oldLevel = getLevel();
            int[] points = {0, 100, 300, 500, 800};
            score += points[Math.min(fullRows.length, 4)] * oldLevel;
            lines += fullRows.length;
        }

        if (aboveBoard) {
            running = false;
            gameOver = true;
            return new StepResult(true, fullRows, true);
        }

        spawnNextPiece();
        if (!canPlace(currentType, rotation, pieceX, pieceY)) {
            running = false;
            gameOver = true;
        }
        return new StepResult(true, fullRows, gameOver);
    }

    private int[] findFullRows() {
        int[] candidates = new int[4];
        int count = 0;
        for (int row = 0; row < ROWS; row++) {
            boolean full = true;
            for (int column = 0; column < COLUMNS; column++) {
                if (board[row][column] == 0) {
                    full = false;
                    break;
                }
            }
            if (full) {
                if (count == candidates.length) {
                    int[] expanded = new int[candidates.length + 4];
                    System.arraycopy(candidates, 0, expanded, 0, candidates.length);
                    candidates = expanded;
                }
                candidates[count++] = row;
            }
        }
        int[] rows = new int[count];
        System.arraycopy(candidates, 0, rows, 0, count);
        return rows;
    }

    private void collapseRows(int[] fullRows) {
        boolean[] remove = new boolean[ROWS];
        for (int row : fullRows) {
            if (row >= 0 && row < ROWS) {
                remove[row] = true;
            }
        }
        int writeRow = ROWS - 1;
        for (int readRow = ROWS - 1; readRow >= 0; readRow--) {
            if (!remove[readRow]) {
                if (writeRow != readRow) {
                    System.arraycopy(board[readRow], 0, board[writeRow], 0, COLUMNS);
                }
                writeRow--;
            }
        }
        while (writeRow >= 0) {
            for (int column = 0; column < COLUMNS; column++) {
                board[writeRow][column] = 0;
            }
            writeRow--;
        }
    }

    private void spawnNextPiece() {
        ensureQueue();
        currentType = nextQueue.removeFirst();
        rotation = 0;
        pieceX = 3;
        pieceY = -1;
        ensureQueue();
    }

    private void ensureQueue() {
        while (nextQueue.size() < 7) {
            List<Integer> bag = new ArrayList<Integer>(PIECE_COUNT);
            for (int type = 0; type < PIECE_COUNT; type++) {
                bag.add(type);
            }
            Collections.shuffle(bag, random);
            for (Integer type : bag) {
                nextQueue.addLast(type);
            }
        }
    }

    private boolean canPlace(int type, int candidateRotation, int candidateX, int candidateY) {
        int[][] blocks = getBlocks(type, candidateRotation);
        for (int[] block : blocks) {
            int x = candidateX + block[0];
            int y = candidateY + block[1];
            if (x < 0 || x >= COLUMNS || y >= ROWS) {
                return false;
            }
            if (y >= 0 && board[y][x] != 0) {
                return false;
            }
        }
        return true;
    }

    static int[][] getBlocks(int type, int rotation) {
        if (type < 0 || type >= PIECE_COUNT) {
            type = 0;
        }
        return SHAPES[type][normalizeRotation(rotation)];
    }

    private static int normalizeRotation(int value) {
        int normalized = value % 4;
        return normalized < 0 ? normalized + 4 : normalized;
    }

    int getGhostY() {
        int ghostY = pieceY;
        while (canPlace(currentType, rotation, pieceX, ghostY + 1)) {
            ghostY++;
        }
        return ghostY;
    }

    int[] getNextPieces(int count) {
        ensureQueue();
        int size = Math.min(Math.max(0, count), nextQueue.size());
        int[] pieces = new int[size];
        int index = 0;
        for (Integer piece : nextQueue) {
            if (index >= size) {
                break;
            }
            pieces[index++] = piece;
        }
        return pieces;
    }

    int[] getSavedQueue() {
        int[] pieces = new int[nextQueue.size()];
        int index = 0;
        for (Integer piece : nextQueue) {
            pieces[index++] = piece;
        }
        return pieces;
    }

    int getCell(int row, int column) {
        return board[row][column];
    }

    int getCurrentType() {
        return currentType;
    }

    int getRotation() {
        return rotation;
    }

    int getPieceX() {
        return pieceX;
    }

    int getPieceY() {
        return pieceY;
    }

    int getScore() {
        return score;
    }

    int getLines() {
        return lines;
    }

    long getGameId() {
        return gameId;
    }

    long getStartedAt() {
        return startedAt;
    }

    int getLevel() {
        return lines / 10 + 1;
    }

    long getDropIntervalMs() {
        return Math.max(90L, 850L - (getLevel() - 1L) * 65L);
    }

    boolean hasStarted() {
        return started;
    }

    boolean isRunning() {
        return running;
    }

    boolean isGameOver() {
        return gameOver;
    }

    boolean hasActiveGame() {
        return started && !gameOver;
    }

    void setRunning(boolean value) {
        running = value && started && !gameOver;
    }

    static final class StepResult {
        static final StepResult NONE = new StepResult(false, new int[0], false);
        static final StepResult MOVED = new StepResult(false, new int[0], false);

        final boolean locked;
        final int[] clearedRows;
        final boolean gameOver;

        StepResult(boolean locked, int[] clearedRows, boolean gameOver) {
            this.locked = locked;
            this.clearedRows = clearedRows == null ? new int[0] : clearedRows;
            this.gameOver = gameOver;
        }
    }
}
