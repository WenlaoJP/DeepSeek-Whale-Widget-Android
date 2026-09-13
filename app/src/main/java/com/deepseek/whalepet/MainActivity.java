package com.deepseek.whalepet;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** 主页：完全对应截图里的「余额小鲸鱼」面板。 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 10;
    /** 一键授权流程进行中：从系统页面/权限弹窗返回后继续申请下一项。 */
    private boolean pendingPermissionFlow;
    // 本轮已跳转过对应系统页/弹窗的标记，避免用户不授予时被反复弹窗
    private boolean askedOverlay;
    private boolean askedNotif;
    private boolean askedBattery;

    private TextView rowOverlay;
    private TextView rowNotify;
    private TextView rowBattery;
    private TextView rowPet;
    private Button btnPet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rowOverlay = (TextView) findViewById(R.id.row_overlay);
        rowNotify = (TextView) findViewById(R.id.row_notify);
        rowBattery = (TextView) findViewById(R.id.row_battery);
        rowPet = (TextView) findViewById(R.id.row_pet);
        btnPet = (Button) findViewById(R.id.btn_pet);

        findViewById(R.id.btn_battery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 重新点按钮 = 重新走一轮，重置已弹标记
                askedOverlay = false;
                askedNotif = false;
                askedBattery = false;
                requestAllPermissions();
            }
        });
        btnPet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePet();
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (pendingPermissionFlow) {
            // 从系统设置页返回后，继续申请下一项权限
            btnPet.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestAllPermissions();
                }
            }, 400L);
        }
    }

    private void refreshStatus() {
        rowOverlay.setText(Settings.canDrawOverlays(this)
                ? R.string.status_overlay_ok : R.string.status_overlay_no);

        boolean notifyGranted = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        rowNotify.setText(notifyGranted ? R.string.status_notify_ok : R.string.status_notify_no);

        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignoring = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        rowBattery.setText(ignoring ? R.string.status_battery_ok : R.string.status_battery_no);

        if (!PetService.running) {
            rowPet.setText(R.string.status_pet_idle);
        } else if (PetService.lastBalance.length() == 0) {
            rowPet.setText(R.string.status_pet_running_unknown);
        } else {
            rowPet.setText(getString(R.string.status_pet_running, PetService.lastBalance));
        }
        btnPet.setText(PetService.running ? R.string.btn_stop : R.string.btn_start);
    }

    private void togglePet() {
        if (PetService.running) {
            Intent stop = new Intent(this, PetService.class);
            stop.setAction(PetService.ACTION_STOP);
            startService(stop);
            btnPet.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            }, 300L);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            return;
        }
        startPetService();
    }

    private void startPetService() {
        Intent intent = new Intent(this, PetService.class);
        intent.setAction(PetService.ACTION_START);
        startForegroundService(intent);
        btnPet.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 600L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            if (pendingPermissionFlow) {
                requestAllPermissions();
            } else {
                startPetService();
            }
        }
    }

    /** 一键按顺序申请：悬浮窗 → 通知 → 忽略电池优化；系统页返回后由 onResume 续下。 */
    private void requestAllPermissions() {
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true;
            pendingPermissionFlow = true;
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            pendingPermissionFlow = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            return;
        }
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean ignoring = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        if (!ignoring && !askedBattery) {
            askedBattery = true;
            pendingPermissionFlow = true;
            requestIgnoreBattery();
            return;
        }
        pendingPermissionFlow = false;
        askedOverlay = false;
        askedNotif = false;
        askedBattery = false;
        boolean ok = Settings.canDrawOverlays(this)
                && power != null && power.isIgnoringBatteryOptimizations(getPackageName())
                && (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        Toast.makeText(this, ok ? "所需权限已就绪" : "部分权限未授予，可再点一次补全", Toast.LENGTH_SHORT).show();
    }

    private void requestOverlayPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable ignored) {
            }
        }
    }

    private void requestIgnoreBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }
}
