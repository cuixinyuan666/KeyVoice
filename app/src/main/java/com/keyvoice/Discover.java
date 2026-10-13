package com.keyvoice;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 局域网自动发现：手机发 KEYVOICE_DISCOVER 广播，电脑接收端回 KEYVOICE_HELLO:<port>。
 *
 * 广播地址按每个网卡的子网定向广播计算（如 192.168.1.255），而不是只发 255.255.255.255：
 * 开着 VPN 时，255.255.255.255 会被隧道劫走，定向广播则走物理网卡路由，能绕开 VPN。
 * 隧道接口（tun/tap/ppp）本身会被排除。
 */
public class Discover {

    public static final int BEACON_PORT = 17891;
    public static final String REQUEST = "KEYVOICE_DISCOVER";
    public static final String REPLY = "KEYVOICE_HELLO";

    public interface Sink {
        void onFound(String ip, int port);
        void onDone(int count);
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
                s.setSoTimeout(600);
                s.bind(new InetSocketAddress(BEACON_PORT));

                // 多轮、多广播地址发送发现请求（电脑端监听到后单播回包）
                byte[] disc = REQUEST.getBytes("UTF-8");
                for (int round = 0; round < 4; round++) {
                    for (String ip : broadcastAddrs()) {
                        try {
                            s.send(new DatagramPacket(disc, disc.length, InetAddress.getByName(ip), BEACON_PORT));
                        } catch (Exception ignored) {
                        }
                    }
                    Thread.sleep(150);
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
