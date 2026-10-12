package com.keyvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

/**
 * 外部触发入口（无需无障碍也能用）：
 *   adb shell am broadcast -a com.keyvoice.TRIGGER -n com.keyvoice/.TriggerReceiver
 *   adb shell am broadcast -a com.keyvoice.SEND_TEXT --es text "你好" -n com.keyvoice/.TriggerReceiver
 * 也可以由 KeyMapper / MacroDroid / Tasker 等发同样的广播。
 */
public class TriggerReceiver extends BroadcastReceiver {

    public static final String ACTION_TRIGGER = "com.keyvoice.TRIGGER";
    public static final String ACTION_SEND_TEXT = "com.keyvoice.SEND_TEXT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (ACTION_SEND_TEXT.equals(a)) {
            String t = intent.getStringExtra("text");
            if (!TextUtils.isEmpty(t)) Sender.send(context, t, null);
        } else if (ACTION_TRIGGER.equals(a)) {
            Trigger.fire(context);
        }
    }
}
