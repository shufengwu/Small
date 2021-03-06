package net.wequick.example.small;

import android.content.SharedPreferences;
import android.os.Build;
import android.support.v7.app.ActionBar;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

import net.wequick.small.Small;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class LaunchActivity extends AppCompatActivity {
    private View mContentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        mContentView = findViewById(R.id.fullscreen_content);

        // Hide UI first
        //隐藏ActionBar
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        // Remove the status and navigation bar
        //隐藏statusbar和navigationbar
        if (Build.VERSION.SDK_INT < 14) return;
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        //如果第一次打开应用，或者刚更新完程序，LaunchActivity显示“Preparing for first launching...”
        if (Small.getIsNewHostApp()) {
            TextView tvPrepare = (TextView) findViewById(R.id.prepare_text);
            tvPrepare.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SharedPreferences sp = this.getSharedPreferences("profile", 0);
        final SharedPreferences.Editor se = sp.edit();
        //setUp开始时间
        se.putLong("setUpStart", System.nanoTime());
        //调用setUp
        Small.setUp(this, new net.wequick.small.Small.OnCompleteListener() {
            @Override
            public void onComplete() {
                //setUp完成时间
                se.putLong("setUpFinish", System.nanoTime()).apply();
                //打开main插件
                Small.openUri("main", LaunchActivity.this);
                finish();
            }
        });
    }
}
