package com.deepseek.whalepet;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：API Key / 刷新间隔 / 阈值 / 大小（0.9~2.5）/ 峰谷文案 / 气泡 / 隐藏菜单按钮 / 开机启动。 */
public class SettingsActivity extends Activity {

    /** 滑块刻度：1~17（0.9× ~ 2.5×，步进 0.1）。 */
    private static final int SCALE_TICKS = Prefs.SCALE_TICKS;

    private EditText inputKey;
    private EditText inputRefresh;
    private EditText inputThreshold;
    private EditText inputSnapEdge;
    private SeekBar seekScale;
    private TextView labelScale;
    private TextView textResult;
    private CheckBox checkBubble;
    private CheckBox checkHideMenu;
    private CheckBox checkMirrorLeft;
    private CheckBox checkTapAdvance;
    private CheckBox checkAuto;
    private CheckBox checkSound;
    private RadioGroup groupPeak;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        inputKey = (EditText) findViewById(R.id.input_key);
        inputRefresh = (EditText) findViewById(R.id.input_refresh);
        inputThreshold = (EditText) findViewById(R.id.input_threshold);
        inputSnapEdge = (EditText) findViewById(R.id.input_snap_edge);
        seekScale = (SeekBar) findViewById(R.id.seek_scale);
        labelScale = (TextView) findViewById(R.id.label_scale_value);
        textResult = (TextView) findViewById(R.id.text_result);
        checkBubble = (CheckBox) findViewById(R.id.check_bubble);
        checkHideMenu = (CheckBox) findViewById(R.id.check_hide_menu);
        checkMirrorLeft = (CheckBox) findViewById(R.id.check_mirror_left);
        checkTapAdvance = (CheckBox) findViewById(R.id.check_tap_advance);
        checkAuto = (CheckBox) findViewById(R.id.check_auto);
        checkSound = (CheckBox) findViewById(R.id.check_sound);
        groupPeak = (RadioGroup) findViewById(R.id.group_peak);

        inputKey.setText(Prefs.apiKey(this));
        inputRefresh.setText(String.valueOf(Prefs.refreshMinutes(this)));
        inputThreshold.setText(String.valueOf(Prefs.threshold(this)));
        inputSnapEdge.setText(String.valueOf(Prefs.snapEdgeDp(this)));
        seekScale.setMax(SCALE_TICKS - 1);
        seekScale.setProgress(Prefs.scaleToDisplay(Prefs.scale(this)) - 1);
        checkBubble.setChecked(Prefs.showBubble(this));
        checkHideMenu.setChecked(Prefs.hideMenu(this));
        checkMirrorLeft.setChecked(Prefs.mirrorLeft(this));
        checkTapAdvance.setChecked(Prefs.tapAdvance(this));
        checkAuto.setChecked(Prefs.autoStart(this));
        checkSound.setChecked(Prefs.soundOn(this));
        String peak = Prefs.peakMode(this);
        groupPeak.check("liangwen".equals(peak) ? R.id.radio_peak_liangwen
                : ("qiangqiang".equals(peak) ? R.id.radio_peak_qiangqiang : R.id.radio_peak_default));
        updateScaleLabel(seekScale.getProgress());

        seekScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateScaleLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                Toast.makeText(SettingsActivity.this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            }
        });

        ((Button) findViewById(R.id.btn_test)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });

        ((Button) findViewById(R.id.btn_sys)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void updateScaleLabel(int progress) {
        int tick = progress + 1;
        float scale = Prefs.displayToScale(tick);
        labelScale.setText(getString(R.string.label_scale) + "：" + tick + "/" + SCALE_TICKS
                + "（" + String.format(java.util.Locale.US, "%.1f", scale) + "×）");
    }

    private void save() {
        Prefs.setApiKey(this, inputKey.getText().toString());
        int minutes = parseInt(inputRefresh.getText().toString(), Prefs.refreshMinutes(this));
        Prefs.setRefreshMinutes(this, minutes);
        float threshold = parseFloat(inputThreshold.getText().toString(), Prefs.threshold(this));
        Prefs.setThreshold(this, threshold);
        int snapEdge = parseInt(inputSnapEdge.getText().toString(), Prefs.snapEdgeDp(this));
        Prefs.setSnapEdgeDp(this, snapEdge);
        Prefs.setScale(this, Prefs.displayToScale(seekScale.getProgress() + 1));
        Prefs.setShowBubble(this, checkBubble.isChecked());
        Prefs.setHideMenu(this, checkHideMenu.isChecked());
        Prefs.setMirrorLeft(this, checkMirrorLeft.isChecked());
        Prefs.setTapAdvance(this, checkTapAdvance.isChecked());
        Prefs.setAutoStart(this, checkAuto.isChecked());
        Prefs.setSoundOn(this, checkSound.isChecked());
        int checked = groupPeak.getCheckedRadioButtonId();
        Prefs.setPeakMode(this, checked == R.id.radio_peak_liangwen ? "liangwen"
                : (checked == R.id.radio_peak_qiangqiang ? "qiangqiang" : "default"));
        applyToRunningPet();
    }

    /** 把配置推给正在运行的挂件；没运行就等下次启动生效。 */
    private void applyToRunningPet() {
        if (!PetService.running) {
            return;
        }
        Intent intent = new Intent(this, PetService.class);
        intent.setAction(PetService.ACTION_CONFIG);
        try {
            startService(intent);
        } catch (Throwable ignored) {
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void testConnection() {
        final String key = inputKey.getText().toString().trim();
        textResult.setText(R.string.toast_testing);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BalanceClient.Result result = BalanceClient.fetch(key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result.ok) {
                            textResult.setText("连接成功 · 当前余额 " + result.display());
                        } else {
                            textResult.setText("连接失败 · " + result.message);
                        }
                    }
                });
            }
        }, "whale-test").start();
    }
}
