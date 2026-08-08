package com.bigsinger.tvtetris;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free smoke tests for the pure Java rules engine. */
public final class GameEngineSelfTest {
    public static void main(String[] args) {
        testSevenBagAndMovement();
        testSnapshotRestoreIsPaused();
        testSingleLineClearAndScoring();
        testHardDrop();
        testLiveLeaderboardUpsert();
        testLockAboveBoardEndsGame();
        System.out.println("GameEngineSelfTest: all checks passed");
    }

    private static void testSevenBagAndMovement() {
        GameEngine engine = new GameEngine();
        engine.newGame();
        assertTrue(engine.hasActiveGame(), "new game should be active");
        assertTrue(engine.isRunning(), "new game should start running");

        boolean[] seen = new boolean[GameEngine.PIECE_COUNT];
        seen[engine.getCurrentType()] = true;
        for (int piece : engine.getNextPieces(6)) {
            assertTrue(!seen[piece], "the first bag must not repeat a piece");
            seen[piece] = true;
        }
        for (boolean present : seen) {
            assertTrue(present, "the first bag must contain all seven pieces");
        }

        int originalX = engine.getPieceX();
        assertTrue(engine.moveHorizontal(-1), "piece should move left after spawning");
        assertEquals(originalX - 1, engine.getPieceX(), "left movement should update x");
        assertTrue(engine.rotateClockwise(), "spawned piece should rotate in open space");
        assertTrue(engine.getGhostY() >= engine.getPieceY(), "ghost cannot be above the piece");
    }

    private static void testSnapshotRestoreIsPaused() {
        GameEngine original = new GameEngine();
        original.newGame();
        original.stepDown(true);
        original.stepDown(true);
        original.moveHorizontal(1);

        int[][] board = copyBoard(original);
        GameEngine restored = new GameEngine();
        boolean success = restored.restore(
                board,
                original.getCurrentType(),
                original.getRotation(),
                original.getPieceX(),
                original.getPieceY(),
                original.getScore(),
                original.getLines(),
                original.getSavedQueue(),
                original.getGameId(),
                original.getStartedAt());

        assertTrue(success, "valid snapshot should restore");
        assertTrue(restored.hasActiveGame(), "restored game should remain active");
        assertTrue(!restored.isRunning(), "restored game must wait in paused state");
        assertEquals(original.getScore(), restored.getScore(), "score should survive restore");
        assertEquals(original.getPieceY(), restored.getPieceY(), "piece position should survive restore");
        assertEquals(original.getGameId(), restored.getGameId(), "game id should survive restore");
        assertEquals(original.getStartedAt(), restored.getStartedAt(), "start date should survive restore");
    }

    private static void testHardDrop() {
        int[][] board = new int[GameEngine.ROWS][GameEngine.COLUMNS];
        GameEngine engine = new GameEngine();
        assertTrue(engine.restore(board, GameEngine.I, 0, 3, 0, 0, 0,
                new int[]{GameEngine.O, GameEngine.T}), "hard-drop setup should restore");
        engine.setRunning(true);
        GameEngine.StepResult result = engine.hardDrop();

        assertTrue(result.locked, "hard drop should immediately lock the piece");
        assertEquals(36, engine.getScore(), "hard drop should award two points per row");
    }

    private static void testLiveLeaderboardUpsert() {
        List<Leaderboard.Entry> history = new ArrayList<Leaderboard.Entry>();
        history.add(new Leaderboard.Entry(11L, 1000, 100L));
        history.add(new Leaderboard.Entry(12L, 900, 90L));

        List<Leaderboard.Entry> ranked = Leaderboard.upsert(history, 99L, 1006, 200L);
        assertEquals(3, ranked.size(), "live score should add one leaderboard row");
        assertEquals(99L, ranked.get(0).gameId, "live 1006 score should become first");
        assertEquals(1000, ranked.get(1).score, "former first place should move to second");

        ranked = Leaderboard.upsert(ranked, 99L, 1200, 200L);
        assertEquals(3, ranked.size(), "updating the same game must not add a duplicate row");
        assertEquals(1200, ranked.get(0).score, "same game should update to its latest score");
        int matchingGameRows = 0;
        for (Leaderboard.Entry entry : ranked) {
            if (entry.gameId == 99L) {
                matchingGameRows++;
            }
        }
        assertEquals(1, matchingGameRows, "one game must occupy exactly one leaderboard row");
    }

    private static void testSingleLineClearAndScoring() {
        int[][] board = new int[GameEngine.ROWS][GameEngine.COLUMNS];
        for (int column = 0; column < 6; column++) {
            board[GameEngine.ROWS - 1][column] = GameEngine.J + 1;
        }
        GameEngine engine = new GameEngine();
        assertTrue(engine.restore(board, GameEngine.I, 0, 6, 18, 0, 0,
                new int[]{GameEngine.O, GameEngine.T}), "line-clear setup should restore");
        engine.setRunning(true);
        GameEngine.StepResult result = engine.stepDown(false);

        assertTrue(result.locked, "grounded piece should lock");
        assertEquals(1, result.clearedRows.length, "exactly one row should clear");
        assertEquals(1, engine.getLines(), "line counter should increment");
        assertEquals(100, engine.getScore(), "single line at level one should award 100");
        for (int column = 0; column < GameEngine.COLUMNS; column++) {
            assertEquals(0, engine.getCell(GameEngine.ROWS - 1, column), "cleared row should collapse");
        }
    }

    private static void testLockAboveBoardEndsGame() {
        int[][] board = new int[GameEngine.ROWS][GameEngine.COLUMNS];
        board[1][1] = GameEngine.Z + 1;
        board[1][2] = GameEngine.Z + 1;
        GameEngine engine = new GameEngine();
        assertTrue(engine.restore(board, GameEngine.O, 0, 0, -1, 0, 0,
                new int[]{GameEngine.I, GameEngine.T}), "top-out setup should restore");
        engine.setRunning(true);
        GameEngine.StepResult result = engine.stepDown(false);

        assertTrue(result.gameOver, "locking above the visible board must end the game");
        assertTrue(engine.isGameOver(), "engine should expose game-over state");
        assertTrue(!engine.hasActiveGame(), "game-over session must not be persisted as active");
    }

    private static int[][] copyBoard(GameEngine engine) {
        int[][] copy = new int[GameEngine.ROWS][GameEngine.COLUMNS];
        for (int row = 0; row < GameEngine.ROWS; row++) {
            for (int column = 0; column < GameEngine.COLUMNS; column++) {
                copy[row][column] = engine.getCell(row, column);
            }
        }
        return copy;
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
