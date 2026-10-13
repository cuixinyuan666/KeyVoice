package com.keyvoice;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashSet;
import java.util.Set;

/** 局域网自动发现：手机发 KEYVOICE_DISCOVER 广播，电脑接收端回 KEYVOICE_HELLO:<port> */
public class Discover {

    public static final int BEACON_PORT = 17891;
    public static final String REQUEST = "KEYVOICE_DISCOVER";
    public static final String REPLY = "KEYVOICE_HELLO";

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
                s.setBroadcast(true);
                s.setSoTimeout(700);
                s.bind(new InetSocketAddress(BEACON_PORT));

                // 主动广播几次发现请求（电脑端监听到后会单播回包）
                byte[] disc = REQUEST.getBytes("UTF-8");
                InetAddress bcast = InetAddress.getByName("255.255.255.255");
                DatagramPacket out = new DatagramPacket(disc, disc.length, bcast, BEACON_PORT);
                for (int i = 0; i < 3; i++) {
                    try { s.send(out); } catch (Exception ignored) { }
                    Thread.sleep(120);
                }

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
                    if (!msg.startsWith(REPLY)) continue;
                    String[] parts = msg.split(":");
                    int port = 17890;
                    if (parts.length > 1) {
                        try {
                            port = Integer.parseInt(parts[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String ip = p.getAddress().getHostAddress();
                    if (ip != null && seen.add(ip + ":" + port)) sink.onFound(ip, port);
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
