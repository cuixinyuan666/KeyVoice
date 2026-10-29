package com.keyvoice;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 局域网自动发现（多设备互发现：电脑 ↔ 手机 ↔ 手机 ↔ 电脑）。
 *
 * <h3>为什么要两套协议</h3>
 * 老版本只有「手机找电脑」：手机往 17891 广播 {@code KEYVOICE_DISCOVER}，
 * 电脑回 {@code KEYVOICE_HELLO:<port>}。这条链路里电脑是唯一的应答方，
 * 于是【后加入的另一台手机永远搜不到】——
 * 手机只在 17893 上应答，而扫描端根本不往 17893 发请求。
 * <p>
 * 现在统一成：扫描端两个端口都广播，应答端在新协议里带上【设备名 + 端口】，
 * 扫描端识别三种回包并按 IP 去重（同一台设备新老协议都会回，取带名字的那条）。
 *
 * <h3>协议</h3>
 * <pre>
 *   老（仅电脑应答）：  发 KEYVOICE_DISCOVER → udp 17891  回 KEYVOICE_HELLO:&lt;port&gt;
 *   新（手机/电脑都应答）：发 KV_DISCOVER        → udp 17893
 *                        回 KV_PC_HERE:&lt;port&gt;:&lt;name&gt;   （电脑）
 *                        回 KV_PHONE_HERE:&lt;port&gt;:&lt;name&gt; （手机）
 * </pre>
 * 新协议解析时兼容「只有名字、没有端口」的更老 build（端口回退到默认）。
 *
 * <h3>广播地址</h3>
 * 按每个网卡的子网定向广播计算（如 192.168.1.255），而不是只发 255.255.255.255：
 * 开着 VPN 时 255.255.255.255 会被隧道劫走，定向广播走物理网卡路由，能绕开 VPN。
 * 隧道接口（tun/tap/ppp）本身会被排除。
 */
public class Discover {

    private static final String TAG = "KeyVoiceDiscover";

    // ---- 老协议端口（老版本 PC 接收端只认这个）----
    public static final int BEACON_PORT = 17891;
    public static final String REQUEST = "KEYVOICE_DISCOVER";
    public static final String REPLY = "KEYVOICE_HELLO";

    // ---- 新协议端口（手机与电脑互发现，应答里带设备名）----
    public static final int DISCOVER_PORT = 17893;
    public static final String NEO_REQUEST = "KV_DISCOVER";
    public static final String NEO_REPLY_PC = "KV_PC_HERE";
    public static final String NEO_REPLY_PHONE = "KV_PHONE_HERE";

    public static final String KIND_PC = "pc";
    public static final String KIND_PHONE = "phone";

    /** 老协议没有端口字段时 / 解析失败时的兜底端口 */
    public static final int DEFAULT_PC_PORT = 17890;
    /** 手机上接收 PC 消息的 HTTP 端口（与 LanServer.PORT 一致） */
    public static final int DEFAULT_PHONE_PORT = 17892;

    /** 一条发现到的设备记录 */
    public static final class Device {
        public final String ip;
        /** 对端接收文字的 HTTP 端口 */
        public final int port;
        /** {@link #KIND_PC} 或 {@link #KIND_PHONE} */
        public final String kind;
        /** 对端自定义的设备名；旧协议可能为空 */
        public final String name;

        Device(String ip, int port, String kind, String name) {
            this.ip = ip == null ? "" : ip;
            this.port = port;
            this.kind = kind;
            this.name = name == null ? "" : name;
        }

        public boolean isPc() { return KIND_PC.equals(kind); }
        public boolean isPhone() { return KIND_PHONE.equals(kind); }

        /** 展示名：有名字用名字，否则退回 IP */
        public String label() {
            return name.trim().isEmpty() ? ip : name.trim();
        }

        /** 类型文案 */
        public String typeLabel() { return isPc() ? "电脑" : "手机"; }

        /** 列表里的一行，例如「DESKTOP-ABC（电脑 192.168.1.5）」 */
        @Override public String toString() {
            return label() + "（" + typeLabel() + " " + ip + "）";
        }
    }

    public interface Sink {
        /** 每发现一台回调一次（在后台线程，UI 需自行切主线程） */
        void onFound(Device d);
        /** 扫描结束，回传【去重后的完整列表】 */
        void onDone(List<Device> all);
    }

    /** 是否开着 VPN（存在 tun/tap/ppp 接口）。VPN 会拦走广播，导致发现失败 */
    public static boolean vpnActive() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp()) continue;
                String n = ifName(ni);
                if (n.contains("tun") || n.contains("tap") || n.contains("ppp") || n.contains("vpn")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String ifName(NetworkInterface ni) {
        String a = ni.getName() == null ? "" : ni.getName();
        String b = ni.getDisplayName() == null ? "" : ni.getDisplayName();
        return (a + " " + b).toLowerCase();
    }

    /**
     * 本机【所有】IPv4 地址。
     * <p>
     * 关键：UDP 广播自己也能收到，所以「过滤自己」不能只比一个 IP——
     * 机器往往有多张网卡（WiFi / 有线 / 虚拟机 / VPN），按路由选出来的那一个，
     * 未必就是发广播时用的那一个；只要漏掉，设备清单里就会多出一台"自己"。
     * 因此这里收集全部本地地址，命中任意一个都算自己。
     * <p>
     * 也不用「UDP connect 8.8.8.8 再取本地地址」那套：它要求真有外网路由，
     * 只连着 WiFi 没外网的场景会直接失败。
     */
    public static Set<String> myIps() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机主 IPv4（跳过隧道网卡）；取不到返回空串。仅用于展示。 */
    public static String myIp() {
        Iterator<String> it = myIps().iterator();
        return it.hasNext() ? it.next() : "";
    }

    /** 所有非隧道网卡的 IPv4 定向广播地址，兜底再加 255.255.255.255 */
    private static Set<String> broadcastAddrs() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) continue;
                String n = ifName(ni);
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
        } catch (Exception ignored) {
        }
        out.add("255.255.255.255");
        return out;
    }

    /**
     * 扫描局域网里的所有 KeyVoice 终端。
     * <p>
     * 回调都在【后台线程】触发，UI 操作请自行切主线程。
     */
    public static void scan(final Context ctx, final int timeoutMs, final Sink sink) {
        new Thread(() -> {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = null;
            if (wm != null) {
                try {
                    lock = wm.createMulticastLock("keyvoice");
                } catch (Exception ignored) {
                    lock = null;
                }
            }
            if (lock != null) {
                try { lock.acquire(); } catch (Exception ignored) { }
            }

            final Map<String, Device> found = new LinkedHashMap<>();
            DatagramSocket s = null;
            try {
                s = openSocket();
                s.setBroadcast(true);
                s.setSoTimeout(400);

                Set<String> mine = myIps();
                byte[] neo = NEO_REQUEST.getBytes("UTF-8");
                byte[] old = REQUEST.getBytes("UTF-8");
                Set<String> bcs = broadcastAddrs();

                // 多轮发送：WiFi 上 UDP 广播丢包很常见，只发一轮经常漏掉后开机的设备。
                // 发送与接收分离（先发完再统一收），避免边发边收时把窗口浪费在等待上。
                for (int round = 0; round < 5; round++) {
                    for (String ip : bcs) {
                        send(s, neo, ip, DISCOVER_PORT);
                        send(s, old, ip, BEACON_PORT);
                    }
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                long end = System.currentTimeMillis() + Math.max(500, timeoutMs);
                byte[] buf = new byte[256];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (Exception e) {
                        // 单次收包异常（个别机型上 ICMP 不可达会以 PortUnreachableException 形式抛出）
                        // 不能让整轮扫描直接失败，否则界面就是「未发现设备」
                        Log.w(TAG, "scan receive: " + e);
                        continue;
                    }
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                    String ip = p.getAddress() == null ? null : p.getAddress().getHostAddress();
                    // 排除自己：本机也会收到自己发的广播并应答
                    if (ip == null || ip.isEmpty() || mine.contains(ip)) continue;
                    Device d = parse(msg, ip);
                    if (d == null) continue;
                    Device prev = found.get(ip);
                    // 同一台设备可能新老协议都回包，优先保留带名字的那条
                    if (prev == null || (prev.name.isEmpty() && !d.name.isEmpty())) {
                        found.put(ip, d);
                        sink.onFound(d);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "scan failed: " + e);
            } finally {
                if (s != null) s.close();
                if (lock != null && lock.isHeld()) {
                    try { lock.release(); } catch (Exception ignored) { }
                }
            }
            sink.onDone(new ArrayList<>(found.values()));
        }, "KeyVoiceScan").start();
    }

    /**
     * 打开用于扫描的 UDP socket。
     * 优先占 17891（老协议端口），被占用时退化到随机端口：对端是【回包到源地址与源端口】，
     * 所以随机端口一样收得到应答，不会让并发的两次扫描互相失败。
     */
    private static DatagramSocket openSocket() throws Exception {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        try {
            s.bind(new InetSocketAddress(BEACON_PORT));
        } catch (Exception e) {
            s.bind(new InetSocketAddress(0));
        }
        return s;
    }

    private static void send(DatagramSocket s, byte[] payload, String ip, int port) {
        try {
            s.send(new DatagramPacket(payload, payload.length, InetAddress.getByName(ip), port));
        } catch (Exception ignored) {
            // 单个广播地址不可达（例如 IPv6-only 网卡算出的地址）不该中断整轮扫描
        }
    }

    /**
     * 解析一条应答报文。
     * 新格式为 {@code KV_PC_HERE:<port>:<name>} / {@code KV_PHONE_HERE:<port>:<name>}；
     * 兼容更老 build 只回 {@code KV_PC_HERE:<name>} 的情况。
     */
    private static Device parse(String msg, String ip) {
        int colon = msg.indexOf(':');
        String head = colon < 0 ? msg : msg.substring(0, colon);
        String rest = colon < 0 ? "" : msg.substring(colon + 1).trim();

        if (REPLY.equals(head)) {                       // 老 PC：KEYVOICE_HELLO:<port>
            return new Device(ip, intOr(rest, DEFAULT_PC_PORT), KIND_PC, "");
        }
        if (NEO_REPLY_PC.equals(head) || NEO_REPLY_PHONE.equals(head)) {
            boolean isPc = NEO_REPLY_PC.equals(head);
            String kind = isPc ? KIND_PC : KIND_PHONE;
            int def = isPc ? DEFAULT_PC_PORT : DEFAULT_PHONE_PORT;
            int c = rest.indexOf(':');
            if (c >= 0) {
                return new Device(ip, intOr(rest.substring(0, c), def), kind,
                        rest.substring(c + 1).trim());
            }
            return new Device(ip, def, kind, rest);
        }
        return null;
    }

    private static int intOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
