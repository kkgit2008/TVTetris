package com.bigsinger.tvtetris;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class GameStorage {
    private static final String PREFERENCES = "tv_tetris_state";
    private static final String KEY_ACTIVE_GAME = "active_game_v1";
    private static final String KEY_LEADERBOARD = "leaderboard_v1";
    private static final int MAX_SCORES = 20;

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
            json.put("version", 1);
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
            if (json.optInt("version", 0) != 1) {
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
                    queue);
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

    void recordGameOverScore(int score) {
        List<ScoreEntry> entries = readLeaderboard();
        entries.add(new ScoreEntry(Math.max(0, score), System.currentTimeMillis()));
        Collections.sort(entries, new Comparator<ScoreEntry>() {
            @Override
            public int compare(ScoreEntry left, ScoreEntry right) {
                if (left.score != right.score) {
                    return left.score < right.score ? 1 : -1;
                }
                return left.timestamp < right.timestamp ? 1 : (left.timestamp == right.timestamp ? 0 : -1);
            }
        });
        if (entries.size() > MAX_SCORES) {
            entries = new ArrayList<ScoreEntry>(entries.subList(0, MAX_SCORES));
        }

        JSONArray array = new JSONArray();
        for (ScoreEntry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("score", entry.score);
                item.put("time", entry.timestamp);
                array.put(item);
            } catch (JSONException ignored) {
                // Primitive values cannot normally fail to serialize.
            }
        }
        preferences.edit().putString(KEY_LEADERBOARD, array.toString()).apply();
    }

    List<ScoreEntry> getLeaderboard() {
        return new ArrayList<ScoreEntry>(readLeaderboard());
    }

    private List<ScoreEntry> readLeaderboard() {
        List<ScoreEntry> entries = new ArrayList<ScoreEntry>();
        String raw = preferences.getString(KEY_LEADERBOARD, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length() && entries.size() < MAX_SCORES; index++) {
                JSONObject item = array.optJSONObject(index);
                if (item != null) {
                    entries.add(new ScoreEntry(
                            Math.max(0, item.optInt("score", 0)),
                            Math.max(0L, item.optLong("time", 0L))));
                }
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(KEY_LEADERBOARD).apply();
        }
        return entries;
    }

    static final class ScoreEntry {
        final int score;
        final long timestamp;

        ScoreEntry(int score, long timestamp) {
            this.score = score;
            this.timestamp = timestamp;
        }

        String formattedDate() {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(timestamp));
        }
    }
}
