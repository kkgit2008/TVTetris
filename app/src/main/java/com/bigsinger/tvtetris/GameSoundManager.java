package com.bigsinger.tvtetris;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;

import java.util.HashSet;

/** Loads and plays the short, user-provided game effects without blocking gameplay. */
final class GameSoundManager {
    private final HashSet<Integer> loadedSamples = new HashSet<Integer>();

    private SoundPool soundPool;
    private int clearOne;
    private int clearMultiExclaim;
    private int clearMultiExclaimTwo;
    private int pieceLand;

    @SuppressWarnings("deprecation")
    GameSoundManager(Context context) {
        try {
            soundPool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool pool, int sampleId, int status) {
                    if (status == 0) {
                        synchronized (loadedSamples) {
                            loadedSamples.add(sampleId);
                        }
                    }
                }
            });
            // User-provided effects: one-line clear, multi-line clears, and piece landing.
            clearOne = soundPool.load(context, R.raw.clear_one, 1);
            clearMultiExclaim = soundPool.load(context, R.raw.clear_multi_exclaim, 1);
            clearMultiExclaimTwo = soundPool.load(context, R.raw.clear_multi_exclaim_2, 1);
            pieceLand = soundPool.load(context, R.raw.piece_land, 1);
        } catch (RuntimeException ignored) {
            release();
        }
    }

    void playPieceLand() {
        play(pieceLand, 0.72f, 1f);
    }

    void playLineClear(int rows) {
        switch (rows) {
            case 1:
                play(clearOne, 0.9f, 1f);
                break;
            case 2:
                play(clearMultiExclaim, 0.95f, 1f);
                break;
            case 3:
                play(clearMultiExclaimTwo, 1f, 1f);
                break;
            default:
                if (rows >= 4) {
                    // Four lines combine both multi-line clips for a distinct Tetris effect.
                    play(clearMultiExclaim, 0.55f, 0.92f);
                    play(clearMultiExclaimTwo, 1f, 1.08f);
                }
                break;
        }
    }

    void release() {
        SoundPool pool = soundPool;
        soundPool = null;
        if (pool != null) {
            pool.release();
        }
        synchronized (loadedSamples) {
            loadedSamples.clear();
        }
    }

    private void play(int sampleId, float volume, float rate) {
        SoundPool pool = soundPool;
        if (pool == null || sampleId == 0) {
            return;
        }
        synchronized (loadedSamples) {
            if (!loadedSamples.contains(sampleId)) {
                return;
            }
        }
        try {
            pool.play(sampleId, volume, volume, 1, 0, rate);
        } catch (RuntimeException ignored) {
            // Sound effects are optional; gameplay must continue if audio is unavailable.
        }
    }
}
