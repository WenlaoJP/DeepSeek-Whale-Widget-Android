package com.deepseek.whalepet;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import java.util.Random;

/**
 * 小音效播放器，音频移植自 whale_v151（res/raw）：
 * - ya1 / ya2：摸头 / 点击时的「呀」系列；
 * - d1 / d2：连戳生气时的「咚」系列打击音。
 * 用 SoundPool 低延迟播放；全部加载完成前不发声，避免首帧无声。
 */
public final class SoundFx {

    private static SoundFx instance;

    private static final int TOTAL = 4;

    private final Context app;
    private final SoundPool pool;
    private final Random random = new Random();
    private final int[] ya = new int[2];
    private final int[] d = new int[2];
    private int loadedCount;

    private SoundFx(Context context) {
        this.app = context.getApplicationContext();
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                if (status == 0) {
                    loadedCount++;
                }
            }
        });
        load(ya, R.raw.ya1, R.raw.ya2);
        load(d, R.raw.d1, R.raw.d2);
    }

    private void load(int[] slot, int id1, int id2) {
        slot[0] = pool.load(app, id1, 1);
        slot[1] = pool.load(app, id2, 1);
    }

    public static synchronized SoundFx get(Context ctx) {
        if (instance == null) {
            instance = new SoundFx(ctx.getApplicationContext());
        }
        return instance;
    }

    /** 摸 / 点鲸鱼：随机一声「呀」。 */
    public void pet() {
        play(ya);
    }

    /** 连戳生气：随机一声「咚」。 */
    public void combo() {
        play(d);
    }

    private void play(int[] ids) {
        if (!Prefs.soundOn(app)) {
            return;
        }
        int id = ids[random.nextInt(ids.length)];
        if (id == 0) {
            return;
        }
        pool.play(id, 1f, 1f, 1, 0, 1f);
    }

    /** 服务退出时释放。 */
    public static synchronized void release() {
        if (instance != null) {
            instance.pool.release();
            instance = null;
        }
    }
}