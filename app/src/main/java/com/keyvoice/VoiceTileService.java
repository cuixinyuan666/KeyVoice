package com.keyvoice;

import android.annotation.TargetApi;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** 下拉快捷设置磁贴：点一下就开始语音输入（亮屏/灭屏都行） */
@TargetApi(Build.VERSION_CODES.N)
public class VoiceTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();
        Trigger.fire(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_ACTIVE);
            t.updateTile();
        }
    }
}
