package com.speedbet.api.common;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

/**
 * MSport-origin detection for MSport-only behaviour on the shared
 * backend. A request is MSport-origin when it carries the
 * {@code X-Client: msport} header (sent by the MSport frontend) or when
 * its Origin/Referer host is megasportst.com (with or without www).
 * Every other skin never matches, so MSport-only checks built on this
 * leave the other skins' behaviour exactly as it is today.
 */
public final class MsportOrigin {

    private MsportOrigin() {
    }

    public static boolean isMsport(HttpServletRequest req) {
        if (req == null) return false;
        String client = req.getHeader("X-Client");
        if (client != null && "msport".equalsIgnoreCase(client.trim())) return true;
        return isMsportHost(req.getHeader("Origin")) || isMsportHost(req.getHeader("Referer"));
    }

    private static boolean isMsportHost(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = new URI(url).getHost();
            return host != null
                    && ("megasportst.com".equalsIgnoreCase(host)
                            || "www.megasportst.com".equalsIgnoreCase(host));
        } catch (Exception e) {
            return false;
        }
    }
}
