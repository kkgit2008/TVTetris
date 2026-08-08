package com.bigsinger.tvtetris;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Pure Java leaderboard ordering and per-game upsert rules. */
final class Leaderboard {
    static final int MAX_ENTRIES = 20;

    private Leaderboard() {
    }

    static List<Entry> upsert(List<Entry> existing, long gameId, int score, long startedAt) {
        ArrayList<Entry> updated = new ArrayList<Entry>();
        boolean replaced = false;
        if (existing != null) {
            for (Entry entry : existing) {
                if (gameId > 0L && entry.gameId == gameId) {
                    if (!replaced) {
                        updated.add(new Entry(
                                gameId,
                                Math.max(entry.score, Math.max(0, score)),
                                entry.timestamp > 0L ? entry.timestamp : startedAt));
                        replaced = true;
                    }
                } else {
                    updated.add(entry);
                }
            }
        }
        if (!replaced) {
            updated.add(new Entry(gameId, Math.max(0, score), Math.max(0L, startedAt)));
        }

        Collections.sort(updated, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                if (left.score != right.score) {
                    return left.score < right.score ? 1 : -1;
                }
                return left.timestamp < right.timestamp
                        ? 1 : (left.timestamp == right.timestamp ? 0 : -1);
            }
        });
        if (updated.size() > MAX_ENTRIES) {
            return new ArrayList<Entry>(updated.subList(0, MAX_ENTRIES));
        }
        return updated;
    }

    static final class Entry {
        final long gameId;
        final int score;
        final long timestamp;

        Entry(long gameId, int score, long timestamp) {
            this.gameId = gameId;
            this.score = score;
            this.timestamp = timestamp;
        }

        String formattedDate() {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(timestamp));
        }
    }
}
