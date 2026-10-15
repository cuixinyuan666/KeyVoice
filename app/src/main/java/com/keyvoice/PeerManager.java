package com.keyvoice;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 手机间局域网互发现 + 群发消息。
 *
 * <p>发现：周期性向子网定向广播发送 {@code KV_PEER_DISCOVER}，对端 LanServer 应答
 * {@code KV_PEER_HERE:<model>:<port>}，据此维护在线对端列表（去重 + 过期清理）。
 *
 * <p>群发：把一条消息 POST 到所有已发现对端的 {@code <ip>:17892/text}（与 PC→手机同一接收端），
 * 对端收到后显示在自己的聊天面板里（来源名 = 发送方的本机名称）。
 *
 * <p>本类是单例，进程内常驻（守护线程），MainActivity 进入前台时挂 UI 回调、退出时摘掉。
 */
public final class PeerManager {

    public static final int DISCOVER_PORT = 17893;
    public static final String REQUEST = "KV_PEER_DISCOVER";
    public static final String REPLY = "KV_PEER_HERE";
    private static final String TAG = "KeyVoicePeer";
    private static final long PEER_TTL_MS = 12000;       // 超过此时间没收到应答则视为离线
    private static final long SCAN_INTERVAL_MS = 3000;  // 每 3 秒广播一轮

    /** 一台在线对端 */
    public static class Peer {
        public final String ip;
        public String model;
        public int port;
        public volatile long lastSeen;
        Peer(String ip, String model, int port) {
            this.ip = ip;
            this.model = model;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private static PeerManager inst;
    public static synchronized PeerManager get() {
        if (inst == null) inst = new PeerManager();
        return inst;
    }

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private String myName = Build.MODEL;
    private Thread scanThread;
    private Context ctx;
    private Handler mainHandler;   // 把监听器回调切到主线程（UI 更新）

    public void setName(String n) { if (n != null && !n.isEmpty()) myName = n; }
    public String name() { return myName; }

    public void addListener(Runnable r) { if (r != null) listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    private void notifyChanged() {
        if (mainHandler == null) {
            try { mainHandler = new Handler(Looper.getMainLooper()); } catch (Exception ignored) { }
        }
        for (Runnable r : listeners) {
            try {
                if (mainHandler != null) mainHandler.post(r);
                else r.run();
            } catch (Exception ignored) { }
        }
    }

    /** 启动发现（幂等）。UI 回调随 MainActivity 生命周期添加/移除。 */
    public synchronized void start(Context ctx) {
        if (ctx != null) this.ctx = ctx.getApplicationContext();
        if (running) return;
        running = true;
        scanThread = new Thread(this::scanLoop, "KVPeerScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (scanThread != null) scanThread.interrupt();
    }

    /** 同局域网设备总数（含本机）。用于 UI 显示「设备数量」。 */
    public int totalCount() {
        prune();
        return peers.size() + 1;
    }

    public int peerCount() {
        prune();
        return peers.size();
    }

    public List<Peer> peerList() {
        prune();
        return new ArrayList<>(peers.values());
    }

    private void prune() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Peer> e : new ArrayList<>(peers.entrySet())) {
            if (now - e.getValue().lastSeen > PEER_TTL_MS) peers.remove(e.getKey());
        }
    }

    private Set<String> ownIps() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 所有非隧道网卡的 IPv4 定向广播地址，兜底再加 255.255.255.255 */
    private Set<String> broadcastAddrs() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) continue;
                String n = (ni.getName() + " " + ni.getDisplayName()).toLowerCase();
                if (n.contains("tun") || n.contains("tap") || n.contains("ppp") || n.contains("vpn")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    int plen = ia.getNetworkPrefixLength();
                    if (plen < 8 || plen > 32) continue;
                    int ip = ByteBuffer.wrap(a.getAddress()).getInt();
                    int mask = plen == 0 ? 0 : (0xffffffff << (32 - plen));
                    int bc = ip | (~mask);
                    out.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(bc).array()).getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        out.add("255.255.255.255");
        return out;
    }

    private void scanLoop() {
        WifiManager wm = null;
        WifiManager.MulticastLock lock = null;
        DatagramSocket s = null;
        try {
            wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                try {
                    lock = wm.createMulticastLock("kvpeer");
                    lock.acquire();
                } catch (Exception ignored) { lock = null; }
            }
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(800);
            Set<String> own = ownIps();
            byte[] req = REQUEST.getBytes(StandardCharsets.UTF_8);
            while (running) {
                // 1) 向各子网定向广播 + 全 255 广播，触发对端应答
                for (String bc : broadcastAddrs()) {
                    try {
                        s.send(new DatagramPacket(req, req.length, InetAddress.getByName(bc), DISCOVER_PORT));
                    } catch (Exception ignored) { }
                }
                // 2) 在 SCAN_INTERVAL 内持续收应答
                long end = System.currentTimeMillis() + SCAN_INTERVAL_MS;
                byte[] buf = new byte[256];
                while (running && System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                        if (!msg.startsWith(REPLY)) continue;
                        String ip = p.getAddress().getHostAddress();
                        if (ip == null || own.contains(ip)) continue;   // 忽略自己
                        String[] parts = msg.split(":");
                        String model = parts.length > 1 ? parts[1] : "手机";
                        int port = LanServer.PORT;
                        if (parts.length > 2) {
                            try { port = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) { }
                        }
                        Peer existing = peers.get(ip);
                        boolean isNew = existing == null;
                        if (existing == null) {
                            peers.put(ip, new Peer(ip, model, port));
                        } else {
                            existing.model = model;
                            existing.port = port;
                            existing.lastSeen = System.currentTimeMillis();
                        }
                        if (isNew) {
                            Log.i(TAG, "发现对端设备: " + ip + " model=" + model + " port=" + port);
                            notifyChanged();
                        }
                    } catch (SocketTimeoutException e) {
                        // 本轮接收超时，继续收或退出内层循环
                    } catch (Exception ignored) { }
                }
                // 3) 清理过期对端
                int before = peers.size();
                prune();
                if (peers.size() != before) notifyChanged();
            }
        } catch (Exception e) {
            Log.w(TAG, "scan loop error: " + e);
        } finally {
            if (s != null) s.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    /** 群发一条消息给所有在线对端（不含本机）。 */
    public void broadcast(final String text, final String from) {
        if (text == null || text.isEmpty()) return;
        final List<Peer> list = peerList();
        if (list.isEmpty()) return;
        new Thread(() -> {
            for (Peer p : list) postTo(p.ip, p.port, text, from);
        }, "KVPeerSend").start();
    }

    private void postTo(String ip, int port, String text, String from) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + "/text");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setDoOutput(true);
            String body = "{\"text\":" + json(text)
                    + ",\"from\":" + json(from)
                    + ",\"ts\":" + System.currentTimeMillis() + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            conn.getResponseCode();
        } catch (Exception e) {
            Log.w(TAG, "post to " + ip + " failed: " + e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
