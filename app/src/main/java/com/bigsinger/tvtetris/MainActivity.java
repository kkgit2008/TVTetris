package com.bigsinger.tvtetris;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final long BACK_EXIT_WINDOW_MS = 2000L;

    private TetrisView gameView;
    private long lastBackPress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        GameStorage storage = new GameStorage(getApplicationContext());
        gameView = new TetrisView(this, storage);
        setContentView(gameView);
        gameView.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN
                && gameView != null
                && gameView.handleRemoteKey(event.getKeyCode(), event.getRepeatCount())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress <= BACK_EXIT_WINDOW_MS) {
            if (gameView != null) {
                gameView.saveBeforeExit();
            }
            finish();
            return;
        }

        lastBackPress = now;
        if (gameView != null) {
            gameView.pauseForBackPress();
        }
        Toast.makeText(this, getString(R.string.back_again_to_exit), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        if (gameView != null) {
            gameView.pauseForLifecycle();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (gameView != null) {
            gameView.saveBeforeExit();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (gameView != null) {
            gameView.release();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LOW_PROFILE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }
}
