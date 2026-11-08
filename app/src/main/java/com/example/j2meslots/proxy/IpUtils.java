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

import androidx.annotation.Nullable;

/** IP literal parsing and CIDR matching without any DNS lookup. */
public final class IpUtils {
	private IpUtils() {
	}

	/** Parses an IPv4 or IPv6 literal; returns null for host names. Never touches DNS. */
	@Nullable
	public static byte[] parseLiteral(@Nullable String s) {
		if (s == null || s.isEmpty()) return null;
		if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
		if (s.indexOf(':') >= 0) return parseV6(s);
		return parseV4(s);
	}

	@Nullable
	static byte[] parseV4(String s) {
		String[] parts = s.split("\\.", -1);
		if (parts.length != 4) return null;
		byte[] out = new byte[4];
		for (int i = 0; i < 4; i++) {
			String p = parts[i];
			if (p.isEmpty() || p.length() > 3) return null;
			int v = 0;
			for (int k = 0; k < p.length(); k++) {
				char c = p.charAt(k);
				if (c < '0' || c > '9') return null;
				v = v * 10 + (c - '0');
			}
			if (v > 255) return null;
			out[i] = (byte) v;
		}
		return out;
	}

	@Nullable
	static byte[] parseV6(String s) {
		int pct = s.indexOf('%');
		if (pct >= 0) s = s.substring(0, pct);
		// Embedded IPv4 tail (::ffff:1.2.3.4)
		byte[] v4tail = null;
		int lastColon = s.lastIndexOf(':');
		if (lastColon >= 0 && s.indexOf('.', lastColon) > 0) {
			v4tail = parseV4(s.substring(lastColon + 1));
			if (v4tail == null) return null;
			s = s.substring(0, lastColon + 1) + "0:0";
		}
		int dbl = s.indexOf("::");
		if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null;
		String[] head;
		String[] tail;
		if (dbl >= 0) {
			String h = s.substring(0, dbl);
			String t = s.substring(dbl + 2);
			head = h.isEmpty() ? new String[0] : h.split(":", -1);
			tail = t.isEmpty() ? new String[0] : t.split(":", -1);
			if (head.length + tail.length > 7) return null;
		} else {
			head = s.split(":", -1);
			tail = new String[0];
			if (head.length != 8) return null;
		}
		int[] groups = new int[8];
		for (int i = 0; i < head.length; i++) {
			int v = hex16(head[i]);
			if (v < 0) return null;
			groups[i] = v;
		}
		for (int i = 0; i < tail.length; i++) {
			int v = hex16(tail[i]);
			if (v < 0) return null;
			groups[8 - tail.length + i] = v;
		}
		byte[] out = new byte[16];
		for (int i = 0; i < 8; i++) {
			out[i * 2] = (byte) (groups[i] >> 8);
			out[i * 2 + 1] = (byte) groups[i];
		}
		if (v4tail != null) System.arraycopy(v4tail, 0, out, 12, 4);
		return out;
	}

	private static int hex16(String g) {
		if (g.isEmpty() || g.length() > 4) return -1;
		int v = 0;
		for (int i = 0; i < g.length(); i++) {
			int d = Character.digit(g.charAt(i), 16);
			if (d < 0) return -1;
			v = (v << 4) | d;
		}
		return v;
	}

	public static boolean isLoopback(byte[] ip) {
		if (ip.length == 4) return (ip[0] & 0xFF) == 127;
		for (int i = 0; i < 15; i++) if (ip[i] != 0) return isMappedLoopback(ip);
		return ip[15] == 1;
	}

	private static boolean isMappedLoopback(byte[] ip) {
		for (int i = 0; i < 10; i++) if (ip[i] != 0) return false;
		return (ip[10] & 0xFF) == 0xFF && (ip[11] & 0xFF) == 0xFF && (ip[12] & 0xFF) == 127;
	}

	/** "a.b.c.d/nn" or "v6/nn" against an address of the same family. */
	public static boolean cidrMatch(String cidr, byte[] ip) {
		int slash = cidr.indexOf('/');
		if (slash < 0) return false;
		byte[] net = parseLiteral(cidr.substring(0, slash));
		if (net == null) return false;
		int bits;
		try {
			bits = Integer.parseInt(cidr.substring(slash + 1).trim());
		} catch (NumberFormatException e) {
			return false;
		}
		if (net.length == 16 && ip.length == 4) {
			ip = mapV4(ip);
		} else if (net.length == 4 && ip.length == 16) {
			byte[] v4 = unmapV4(ip);
			if (v4 == null) return false;
			ip = v4;
		}
		if (bits < 0 || bits > net.length * 8) return false;
		for (int i = 0; i < bits; i++) {
			int mask = 0x80 >> (i % 8);
			if ((net[i / 8] & mask) != (ip[i / 8] & mask)) return false;
		}
		return true;
	}

	private static byte[] mapV4(byte[] v4) {
		byte[] out = new byte[16];
		out[10] = (byte) 0xFF;
		out[11] = (byte) 0xFF;
		System.arraycopy(v4, 0, out, 12, 4);
		return out;
	}

	@Nullable
	private static byte[] unmapV4(byte[] v6) {
		for (int i = 0; i < 10; i++) if (v6[i] != 0) return null;
		if ((v6[10] & 0xFF) != 0xFF || (v6[11] & 0xFF) != 0xFF) return null;
		byte[] out = new byte[4];
		System.arraycopy(v6, 12, out, 0, 4);
		return out;
	}
}
