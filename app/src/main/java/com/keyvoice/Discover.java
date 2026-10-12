package com.keyvoice;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.Set;

/** 监听电脑端发来的 UDP 广播，自动发现局域网里的 Windows 接收端 */
public class Discover {

    public static final int BEACON_PORT = 17891;

    public interface Sink {
        void onFound(String ip, int port);
        void onDone(int count);
    }

    public static void scan(final Context ctx, final int timeoutMs, final Sink sink) {
        new Thread(() -> {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            if (wm != null) {
                try {
                    lock = wm.createMulticastLock("keyvoice");
                    lock.acquire();
                } catch (Exception ignored) {
                    lock = null;
                }
            }

            DatagramSocket s = null;
            Set<String> seen = new LinkedHashSet<>();
            try {
                s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setSoTimeout(700);
                s.bind(new InetSocketAddress(BEACON_PORT));

                long end = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[256];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                    if (!msg.startsWith("KEYVOICE_HELLO")) continue;
                    String[] parts = msg.split(":");
                    int port = 17890;
                    if (parts.length > 1) {
                        try {
                            port = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String ip = p.getAddress().getHostAddress();
                    if (seen.add(ip + ":" + port)) sink.onFound(ip, port);
                }
            } catch (Exception ignored) {
            } finally {
                if (s != null) s.close();
                if (lock != null && lock.isHeld()) lock.release();
            }
            sink.onDone(seen.size());
        }).start();
    }
}
