/*
 * Copyright 2026 J2ME Slot Runner contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.j2meslots.proxy;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable proxy configuration entered by the user. Pure Java (except the Bundle helpers) so it
 * can be unit tested on the JVM.
 * <p>
 * {@link #toString()} never contains the password.
 */
public final class ProxyConfig {

	public enum Type {
		/** Classic HTTP proxy: plain http requests are forwarded, everything else uses CONNECT. */
		HTTP("http"),
		/** HTTP proxy used only through CONNECT tunnels (for all traffic, including plain http). */
		HTTPS_CONNECT("https-connect"),
		/** SOCKS5 (RFC 1928) with optional username/password (RFC 1929); DNS resolved by the proxy. */
		SOCKS5("socks5");

		public final String scheme;

		Type(String scheme) {
			this.scheme = scheme;
		}

		@Nullable
		public static Type fromScheme(String s) {
			if (s == null) return null;
			switch (s.toLowerCase(Locale.ROOT)) {
				case "http":
					return HTTP;
				case "https-connect":
				case "connect":
				case "https":
					return HTTPS_CONNECT;
				case "socks5":
				case "socks5h":
				case "socks":
					return SOCKS5;
				default:
					return null;
			}
		}
	}

	public final Type type;
	public final String host;
	public final int port;
	public final String user;
	public final String password;
	/** Raw bypass list as typed by the user (comma / space / newline separated). */
	public final String bypass;
	private final List<String> bypassEntries;

	public ProxyConfig(@NonNull Type type, @NonNull String host, int port,
					   @Nullable String user, @Nullable String password, @Nullable String bypass) {
		this.type = type;
		this.host = host.trim();
		this.port = port;
		this.user = user == null ? "" : user;
		this.password = password == null ? "" : password;
		this.bypass = bypass == null ? "" : bypass.trim();
		this.bypassEntries = parseBypass(this.bypass);
	}

	public boolean hasCredentials() {
		return !user.isEmpty();
	}

	/** Returns null when the configuration is usable, else a short reason (for validation UI). */
	@Nullable
	public String validate() {
		if (host.isEmpty()) return "host";
		if (host.contains(" ") || host.contains("/")) return "host";
		if (port < 1 || port > 65535) return "port";
		if (user.length() > 255 || password.length() > 255) return "credentials";
		return null;
	}

	// ------------------------------------------------------------------ bypass

	static List<String> parseBypass(String raw) {
		if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (String s : raw.split("[,;\\s]+")) {
			s = s.trim().toLowerCase(Locale.ROOT);
			if (!s.isEmpty()) out.add(s);
		}
		return out;
	}

	public List<String> bypassEntries() {
		return Collections.unmodifiableList(bypassEntries);
	}

	/**
	 * True when {@code host} must be reached directly. Loopback is always direct.
	 * Entries: exact host ("example.com"), suffix ("*.example.com" or ".example.com"),
	 * IP ("10.0.0.1", "::1") or CIDR ("192.168.0.0/16", "fd00::/8"). Host names are never
	 * resolved for matching (no DNS leak).
	 */
	public boolean isBypassed(@Nullable String host) {
		if (host == null) return false;
		String h = host.trim().toLowerCase(Locale.ROOT);
		if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
		if (h.equals("localhost") || h.endsWith(".localhost")) return true;
		byte[] ip = IpUtils.parseLiteral(h);
		if (ip != null && IpUtils.isLoopback(ip)) return true;
		for (String e : bypassEntries) {
			if (e.equals("*")) return true;
			if (e.contains("/")) {
				if (ip != null && IpUtils.cidrMatch(e, ip)) return true;
			} else if (e.startsWith("*.")) {
				String suffix = e.substring(1); // ".example.com"
				if (h.endsWith(suffix) || h.equals(e.substring(2))) return true;
			} else if (e.startsWith(".")) {
				if (h.endsWith(e) || h.equals(e.substring(1))) return true;
			} else if (ip != null) {
				byte[] other = IpUtils.parseLiteral(e);
				if (other != null && java.util.Arrays.equals(other, ip)) return true;
			} else if (h.equals(e)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ URI form

	/** {@code scheme://user:pass@host:port} (user/pass percent-encoded). */
	public String toUri(boolean includePassword) {
		StringBuilder sb = new StringBuilder(type.scheme).append("://");
		if (hasCredentials()) {
			sb.append(enc(user));
			if (includePassword && !password.isEmpty()) sb.append(':').append(enc(password));
			sb.append('@');
		}
		sb.append(host.contains(":") ? "[" + host + "]" : host).append(':').append(port);
		return sb.toString();
	}

	/**
	 * Parses {@code scheme://user:pass@host:port}. Supported schemes: http, https-connect
	 * (alias connect, https), socks5 (alias socks5h, socks). Bypass list is kept from {@code bypass}.
	 *
	 * @throws IllegalArgumentException with a short reason
	 */
	public static ProxyConfig parseUri(@NonNull String uri, @Nullable String bypass) {
		String s = uri.trim();
		int schemeEnd = s.indexOf("://");
		if (schemeEnd <= 0) throw new IllegalArgumentException("scheme");
		Type type = Type.fromScheme(s.substring(0, schemeEnd));
		if (type == null) throw new IllegalArgumentException("scheme");
		String rest = s.substring(schemeEnd + 3);
		int slash = rest.indexOf('/');
		if (slash >= 0) rest = rest.substring(0, slash);
		String user = "";
		String pass = "";
		int at = rest.lastIndexOf('@');
		if (at >= 0) {
			String cred = rest.substring(0, at);
			rest = rest.substring(at + 1);
			int colon = cred.indexOf(':');
			if (colon >= 0) {
				user = dec(cred.substring(0, colon));
				pass = dec(cred.substring(colon + 1));
			} else {
				user = dec(cred);
			}
		}
		String host;
		String portStr;
		if (rest.startsWith("[")) {
			int close = rest.indexOf(']');
			if (close < 0) throw new IllegalArgumentException("host");
			host = rest.substring(1, close);
			portStr = rest.length() > close + 1 && rest.charAt(close + 1) == ':' ? rest.substring(close + 2) : "";
		} else {
			int colon = rest.lastIndexOf(':');
			if (colon < 0) {
				host = rest;
				portStr = "";
			} else {
				host = rest.substring(0, colon);
				portStr = rest.substring(colon + 1);
			}
		}
		int port;
		if (portStr.isEmpty()) {
			port = type == Type.SOCKS5 ? 1080 : 8080;
		} else {
			try {
				port = Integer.parseInt(portStr);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("port");
			}
		}
		ProxyConfig c = new ProxyConfig(type, host, port, user, pass, bypass);
		String err = c.validate();
		if (err != null) throw new IllegalArgumentException(err);
		return c;
	}

	private static String enc(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			throw new AssertionError(e);
		}
	}

	private static String dec(String s) {
		try {
			return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			return s;
		}
	}

	// ------------------------------------------------------------------ Bundle (Intent extras)

	private static final String B_TYPE = "type";
	private static final String B_HOST = "host";
	private static final String B_PORT = "port";
	private static final String B_USER = "user";
	private static final String B_PASS = "pass";
	private static final String B_BYPASS = "bypass";

	public Bundle toBundle() {
		Bundle b = new Bundle();
		b.putString(B_TYPE, type.name());
		b.putString(B_HOST, host);
		b.putInt(B_PORT, port);
		b.putString(B_USER, user);
		b.putString(B_PASS, password);
		b.putString(B_BYPASS, bypass);
		return b;
	}

	@Nullable
	public static ProxyConfig fromBundle(@Nullable Bundle b) {
		if (b == null) return null;
		try {
			Type type = Type.valueOf(b.getString(B_TYPE, ""));
			String host = b.getString(B_HOST, "");
			ProxyConfig c = new ProxyConfig(type, host, b.getInt(B_PORT),
					b.getString(B_USER), b.getString(B_PASS), b.getString(B_BYPASS));
			return c.validate() == null ? c : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Redacted description, safe for logs. */
	@NonNull
	@Override
	public String toString() {
		return type.scheme + "://" + (hasCredentials() ? "***:***@" : "") + host + ":" + port
				+ (bypassEntries.isEmpty() ? "" : " bypass=" + bypassEntries.size());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ProxyConfig)) return false;
		ProxyConfig c = (ProxyConfig) o;
		return port == c.port && type == c.type && host.equals(c.host) && user.equals(c.user)
				&& password.equals(c.password) && bypass.equals(c.bypass);
	}

	@Override
	public int hashCode() {
		return ((type.hashCode() * 31 + host.hashCode()) * 31 + port) * 31 + user.hashCode();
	}
}
