package com.bigsinger.tvtetris;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class GameStorage {
    private static final String PREFERENCES = "tv_tetris_state";
    private static final String KEY_ACTIVE_GAME = "active_game_v1";
    private static final String KEY_LEADERBOARD = "leaderboard_v1";

    private final SharedPreferences preferences;

    GameStorage(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    void saveActiveGame(GameEngine engine) {
        if (engine == null || !engine.hasActiveGame()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("version", 2);
            StringBuilder board = new StringBuilder(GameEngine.ROWS * GameEngine.COLUMNS);
            for (int row = 0; row < GameEngine.ROWS; row++) {
                for (int column = 0; column < GameEngine.COLUMNS; column++) {
                    board.append((char) ('0' + engine.getCell(row, column)));
                }
            }
            json.put("board", board.toString());
            json.put("type", engine.getCurrentType());
            json.put("rotation", engine.getRotation());
            json.put("x", engine.getPieceX());
            json.put("y", engine.getPieceY());
            json.put("score", engine.getScore());
            json.put("lines", engine.getLines());
            json.put("gameId", engine.getGameId());
            json.put("startedAt", engine.getStartedAt());

            JSONArray queue = new JSONArray();
            for (int piece : engine.getSavedQueue()) {
                queue.put(piece);
            }
            json.put("queue", queue);
            preferences.edit().putString(KEY_ACTIVE_GAME, json.toString()).apply();
        } catch (JSONException ignored) {
            // All values are primitive and should be serializable. Keep the previous save if not.
        }
    }

    boolean restoreActiveGame(GameEngine engine) {
        String raw = preferences.getString(KEY_ACTIVE_GAME, null);
        if (raw == null || engine == null) {
            return false;
        }
        try {
            JSONObject json = new JSONObject(raw);
            int version = json.optInt("version", 0);
            if (version < 1 || version > 2) {
                clearActiveGame();
                return false;
            }
            String encodedBoard = json.getString("board");
            if (encodedBoard.length() != GameEngine.ROWS * GameEngine.COLUMNS) {
                clearActiveGame();
                return false;
            }
            int[][] board = new int[GameEngine.ROWS][GameEngine.COLUMNS];
            int offset = 0;
            for (int row = 0; row < GameEngine.ROWS; row++) {
                for (int column = 0; column < GameEngine.COLUMNS; column++) {
                    board[row][column] = encodedBoard.charAt(offset++) - '0';
                }
            }

            JSONArray queueJson = json.optJSONArray("queue");
            int[] queue = new int[queueJson == null ? 0 : queueJson.length()];
            for (int index = 0; index < queue.length; index++) {
                queue[index] = queueJson.optInt(index, -1);
            }

            boolean restored = engine.restore(
                    board,
                    json.getInt("type"),
                    json.optInt("rotation", 0),
                    json.getInt("x"),
                    json.getInt("y"),
                    json.optInt("score", 0),
                    json.optInt("lines", 0),
                    queue,
                    json.optLong("gameId", 0L),
                    json.optLong("startedAt", 0L));
            if (!restored) {
                clearActiveGame();
            }
            return restored;
        } catch (JSONException ignored) {
            clearActiveGame();
            return false;
        }
    }

    void clearActiveGame() {
        preferences.edit().remove(KEY_ACTIVE_GAME).apply();
    }

    void updateLeaderboard(GameEngine engine) {
        if (engine == null || !engine.hasStarted()) {
            return;
        }
        List<Leaderboard.Entry> entries = Leaderboard.upsert(
                readLeaderboard(),
                engine.getGameId(),
                engine.getScore(),
                engine.getStartedAt());
        writeLeaderboard(entries);
    }

    private void writeLeaderboard(List<Leaderboard.Entry> entries) {
        JSONArray array = new JSONArray();
        for (Leaderboard.Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("gameId", entry.gameId);
                item.put("score", entry.score);
                item.put("time", entry.timestamp);
                array.put(item);
            } catch (JSONException ignored) {
                // Primitive values cannot normally fail to serialize.
            }
        }
        preferences.edit().putString(KEY_LEADERBOARD, array.toString()).apply();
    }

    List<Leaderboard.Entry> getLeaderboard() {
        return new ArrayList<Leaderboard.Entry>(readLeaderboard());
    }

    private List<Leaderboard.Entry> readLeaderboard() {
        List<Leaderboard.Entry> entries = new ArrayList<Leaderboard.Entry>();
        String raw = preferences.getString(KEY_LEADERBOARD, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length()
                    && entries.size() < Leaderboard.MAX_ENTRIES; index++) {
                JSONObject item = array.optJSONObject(index);
                if (item != null) {
                    entries.add(new Leaderboard.Entry(
                            Math.max(0L, item.optLong("gameId", 0L)),
                            Math.max(0, item.optInt("score", 0)),
                            Math.max(0L, item.optLong("time", 0L))));
                }
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_LEADERBOARD).apply();
        }
        return entries;
    }
}
