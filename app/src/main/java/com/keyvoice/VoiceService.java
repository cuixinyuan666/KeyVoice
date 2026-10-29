package com.keyvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 常驻前台服务：负责"灭屏也能听写"。
 *
 * Android 9 起后台应用拿不到麦克风，所以必须以前台服务身份运行；
 * 同时持有 CPU 唤醒锁 + WiFi 锁，保证灭屏时录音与网络都不中断。
 * 整个过程没有界面，屏幕保持熄灭。
 */
public class VoiceService extends Service {

    public static final String ACTION_KEEP = "com.keyvoice.KEEP";
    public static final String ACTION_START = "com.keyvoice.START";
    public static final String ACTION_STOP = "com.keyvoice.STOP";
    public static final String ACTION_EXIT = "com.keyvoice.EXIT";
    public static final String ACTION_RESULT = "com.keyvoice.RESULT";

    private static final String CHANNEL = "keyvoice";
    private static final int NOTIF_ID = 1789;

    static boolean running = false;

    private SpeechRecognizer sr;
    private boolean listening = false;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSession mediaSession;
    private Handler main;
    private Vibrator vibrator;
    /** 最近一次已发送的文本与时间：识别引擎对同一段长语音可能回调多次 onResults，
     *  用「相同文本 + 短时间窗」去重，避免同一段文字被发送/上屏两次 */
    private String lastSentText = null;
    private long lastSentAt = 0;
    /** 结果处理中标志：防引擎在销毁窗口内的重复回调并发进入 */
    private volatile boolean handlingResult = false;

    // ------------------------------------------------------------ 入口

    public static void startListening(Context c) {
        Intent i = new Intent(c, VoiceService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Trigger.toast(c, "启动服务失败：" + e.getMessage());
        }
    }

    public static void keep(Context c) {
        Intent i = new Intent(c, VoiceService.class).setAction(ACTION_KEEP);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        createChannel();
        setupMediaSession();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("待命中"));

        String a = intent != null ? intent.getAction() : null;
        Config cfg = new Config(this);

        if (ACTION_EXIT.equals(a)) {
            stopListen();
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(a)) {
            stopListen();
            if (!cfg.resident()) shutdown();
            return cfg.resident() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_START.equals(a)) {
            if (listening) stopListen();
            else beginListen();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopListen();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void shutdown() {
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------ 听写

    private void beginListen() {
        if (listening) return;
        Config cfg = new Config(this);

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Trigger.toast(this, "请先在 App 里授予麦克风权限");
            return;
        }

        acquireLocks();

        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag());
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.CHINA.toLanguageTag());
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        it.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        destroySr();
        String comp = cfg.srComponent();
        if (!TextUtils.isEmpty(comp)) {
            ComponentName cn = ComponentName.unflattenFromString(comp);
            sr = (cn != null)
                    ? SpeechRecognizer.createSpeechRecognizer(this, cn)
                    : SpeechRecognizer.createSpeechRecognizer(this);
        } else {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
        }
        sr.setRecognitionListener(listener);
        sr.startListening(it);
        listening = true;
        updateNotification("聆听中…");
        buzz(25);
    }

    private void stopListen() {
        listening = false;
        destroySr();
        releaseLocks();
        updateNotification("待命中");
    }

    private void destroySr() {
        if (sr != null) {
            try {
                sr.cancel();
            } catch (Exception ignored) {
            }
            try {
                sr.destroy();
            } catch (Exception ignored) {
            }
            sr = null;
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }

        @Override public void onBeginningOfSpeech() { }

        @Override public void onRmsChanged(float rmsdB) { }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override public void onEndOfSpeech() {
            updateNotification("识别中…");
        }

        @Override public void onError(int error) {
            listening = false;
            destroySr();
            releaseLocks();
            Config cfg = new Config(VoiceService.this);
            if (cfg.continuous()
                    && (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                main.postDelayed(() -> beginListen(), 400);
            } else {
                updateNotification("待命中");
                if (!cfg.resident()) shutdown();
            }
        }

        @Override public void onResults(Bundle results) {
            handleResult(results, true);
        }

        @Override public void onPartialResults(Bundle partialResults) {
            // 实时结果先不发送，避免半句上屏
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    };

    private void handleResult(Bundle bundle, boolean broadcast) {
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
        // 防重入：同一段语音长/多字时，识别引擎可能在销毁窗口内连续回调多次 onResults，
        // 并发进入会各自走一遍发送 → 电脑端收到重复的两份。这里用标志挡掉并发第二次。
        if (handlingResult) return;
        handlingResult = true;
        try {
            handleResultInner(list, text, broadcast);
        } finally {
            handlingResult = false;
        }
    }

    private void handleResultInner(ArrayList<String> list, String text, boolean broadcast) {
        listening = false;
        destroySr();
        releaseLocks();

        if (TextUtils.isEmpty(text)) {
            updateNotification("待命中");
            if (new Config(this).continuous()) main.postDelayed(() -> beginListen(), 400);
            return;
        }

        // 相同文本去重：识别引擎对同一段语音偶发「结果回调后又回调一次同样文本」
        // （长录音尤其常见），8 秒窗口内同样的文字视为重复，丢弃不再发第二份。
        long now = System.currentTimeMillis();
        if (text.equals(lastSentText) && now - lastSentAt < 8000) {
            updateNotification("待命中");
            if (new Config(this).continuous()) main.postDelayed(() -> beginListen(), 500);
            else if (!new Config(this).resident()) shutdown();
            return;
        }
        lastSentText = text;
        lastSentAt = now;

        Config cfg = new Config(this);
        String out = cfg.addNewline() ? text + "\n" : text;
        updateNotification("发送中…");
        Sender.send(this, out, (ok, msg) -> {
            updateNotification(ok ? "已发送：" + text : "发送失败：" + msg);
            if (ok) buzz(45);
            if (broadcast) {
                sendBroadcast(new Intent(ACTION_RESULT)
                        .putExtra("text", out)
                        .putExtra("ok", ok)
                        .setPackage(getPackageName()));
            }
            if (cfg.continuous()) main.postDelayed(() -> beginListen(), 500);
            else if (!cfg.resident()) shutdown();
        });
    }

    // -------------------------------------------------------- 媒体键触发

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "KeyVoice");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent ke = mediaButtonIntent != null
                        ? mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) : null;
                if (ke == null) return false;
                int code = ke.getKeyCode();
                if (code != KeyEvent.KEYCODE_HEADSETHOOK
                        && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        && code != KeyEvent.KEYCODE_MEDIA_PLAY) return false;
                if (ke.getAction() != KeyEvent.ACTION_UP) return true;
                if (!new Config(VoiceService.this).mediaKey()) return false;
                main.post(() -> {
                    if (listening) stopListen();
                    else beginListen();
                });
                return true;
            }
        });
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
        mediaSession.setActive(true);
    }

    // ------------------------------------------------------------ 锁

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeyVoice:Mic");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(3 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        // 锁屏听写：点亮屏幕（dim），否则 SpeechRecognizer 在息屏时会被系统停掉
        if (new Config(this).keepScreen()) {
            try {
                if (screenWakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    screenWakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_DIM_WAKE_LOCK
                                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "KeyVoice:Screen");
                    screenWakeLock.setReferenceCounted(false);
                }
                if (!screenWakeLock.isHeld()) screenWakeLock.acquire(3 * 60 * 1000L);
            } catch (Exception ignored) {
            }
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KeyVoice:Net");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private void buzz(int ms) {
        if (!new Config(this).vibrate()) return;
        try {
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "按键语音",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("常驻听写服务");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(this, VoiceService.class).setAction(ACTION_START);
        PendingIntent piToggle = PendingIntent.getService(this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent exit = new Intent(this, VoiceService.class).setAction(ACTION_EXIT);
        PendingIntent piExit = PendingIntent.getService(this, 3, exit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("按键语音")
                .setContentText(text)
                .setContentIntent(piOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // 锁屏上也完整显示（含「听写/停止」按钮）—— 锁屏触发就靠它
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat),
                    listening ? "停止" : "听写", piToggle).build());
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat),
                    "退出", piExit).build());
        }
        return nb.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
