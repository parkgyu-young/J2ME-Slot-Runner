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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * TCP connections through a user-configured proxy. Implemented by hand (instead of
 * {@code new Socket(Proxy)}) so that it behaves the same on Android 5..15:
 * <ul>
 *     <li>SOCKS5 always sends the host <b>name</b> (ATYP 3): DNS is resolved by the proxy.</li>
 *     <li>SOCKS5 username/password (RFC 1929) and HTTP Basic proxy auth.</li>
 *     <li>Every failure becomes a {@link ProxyException} with a precise reason.</li>
 * </ul>
 * UDP (datagram://) and Bluetooth are never proxied.
 */
public final class ProxySockets {
	public static final int CONNECT_TIMEOUT = 20_000;
	public static final int HANDSHAKE_TIMEOUT = 20_000;
	private static final int MAX_LINE = 16 * 1024;

	private ProxySockets() {
	}

	/**
	 * Socket to {@code host:port} for the GCF {@code socket://} / {@code ssl://} connections of the
	 * current slot: direct when no proxy is configured or the host is bypassed.
	 */
	public static Socket connect(@NonNull String host, int port) throws IOException {
		ProxyConfig p = ProxyHolder.forHost(host);
		if (p == null) {
			return new Socket(host, port);
		}
		try {
			return openTunnel(p, host, port, CONNECT_TIMEOUT);
		} catch (ProxyException e) {
			ProxyHolder.report(e);
			throw e;
		}
	}

	/** TCP connection to the proxy itself (no handshake). */
	public static Socket connectToProxy(@NonNull ProxyConfig p, int timeout) throws ProxyException {
		InetSocketAddress addr = new InetSocketAddress(p.host, p.port);
		if (addr.isUnresolved()) {
			throw new ProxyException(ProxyException.Kind.PROXY_DNS, p.host);
		}
		Socket s = new Socket();
		try {
			s.connect(addr, timeout);
			s.setTcpNoDelay(true);
			return s;
		} catch (SocketTimeoutException e) {
			closeQuietly(s);
			throw new ProxyException(ProxyException.Kind.PROXY_TIMEOUT, e, p.host, p.port);
		} catch (UnknownHostException e) {
			closeQuietly(s);
			throw new ProxyException(ProxyException.Kind.PROXY_DNS, e, p.host);
		} catch (ConnectException | NoRouteToHostException e) {
			closeQuietly(s);
			throw new ProxyException(ProxyException.Kind.PROXY_REFUSED, e, p.host, p.port, String.valueOf(e.getMessage()));
		} catch (IOException e) {
			closeQuietly(s);
			throw new ProxyException(ProxyException.Kind.PROXY_REFUSED, e, p.host, p.port, String.valueOf(e.getMessage()));
		}
	}

	/** Opens a tunnel to {@code host:port} (SOCKS5 CONNECT or HTTP CONNECT). */
	public static Socket openTunnel(@NonNull ProxyConfig p, @NonNull String host, int port, int timeout)
			throws IOException {
		Socket s = connectToProxy(p, timeout);
		try {
			s.setSoTimeout(HANDSHAKE_TIMEOUT);
			if (p.type == ProxyConfig.Type.SOCKS5) {
				socks5Connect(s.getInputStream(), s.getOutputStream(), p, host, port);
			} else {
				httpConnect(s.getInputStream(), s.getOutputStream(), p, host, port);
			}
			s.setSoTimeout(0);
			return s;
		} catch (SocketTimeoutException e) {
			closeQuietly(s);
			throw new ProxyException(ProxyException.Kind.PROXY_TIMEOUT, e, p.host, p.port);
		} catch (IOException | RuntimeException e) {
			closeQuietly(s);
			throw e;
		}
	}

	// ------------------------------------------------------------------ SOCKS5

	/** RFC 1928 + RFC 1929 client handshake on an already connected stream pair. */
	static void socks5Connect(InputStream in, OutputStream out, ProxyConfig p, String host, int port)
			throws IOException {
		boolean auth = p.hasCredentials();
		out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
		out.flush();
		int ver = readByte(in, p, ProxyException.Kind.NOT_SOCKS5);
		int method = readByte(in, p, ProxyException.Kind.NOT_SOCKS5);
		if (ver != 5) throw new ProxyException(ProxyException.Kind.NOT_SOCKS5, p.host, p.port);
		if (method == 0x02) {
			if (!auth) throw new ProxyException(ProxyException.Kind.AUTH_REQUIRED, p.host, p.port);
			byte[] u = p.user.getBytes(StandardCharsets.UTF_8);
			byte[] pw = p.password.getBytes(StandardCharsets.UTF_8);
			if (u.length > 255 || pw.length > 255) {
				throw new ProxyException(ProxyException.Kind.AUTH_FAILED, p.host, p.port);
			}
			ByteArrayOutputStream req = new ByteArrayOutputStream();
			req.write(1);
			req.write(u.length);
			req.write(u, 0, u.length);
			req.write(pw.length);
			req.write(pw, 0, pw.length);
			out.write(req.toByteArray());
			out.flush();
			readByte(in, p, ProxyException.Kind.PROXY_CLOSED); // sub-negotiation version
			int status = readByte(in, p, ProxyException.Kind.AUTH_FAILED);
			if (status != 0) throw new ProxyException(ProxyException.Kind.AUTH_FAILED, p.host, p.port);
		} else if (method == 0xFF) {
			throw new ProxyException(auth ? ProxyException.Kind.AUTH_UNSUPPORTED
					: ProxyException.Kind.AUTH_REQUIRED, p.host, p.port);
		} else if (method != 0x00) {
			throw new ProxyException(ProxyException.Kind.AUTH_UNSUPPORTED, p.host, p.port);
		}

		ByteArrayOutputStream req = new ByteArrayOutputStream();
		req.write(5);
		req.write(1); // CONNECT
		req.write(0);
		byte[] ip = IpUtils.parseLiteral(host);
		if (ip != null) {
			req.write(ip.length == 4 ? 1 : 4);
			req.write(ip, 0, ip.length);
		} else {
			// Domain name: resolved by the proxy (no local DNS query)
			byte[] name = IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);
			if (name.length > 255) throw new UnknownHostException(host);
			req.write(3);
			req.write(name.length);
			req.write(name, 0, name.length);
		}
		req.write((port >> 8) & 0xFF);
		req.write(port & 0xFF);
		out.write(req.toByteArray());
		out.flush();

		int rver = readByte(in, p, ProxyException.Kind.PROXY_CLOSED);
		int rep = readByte(in, p, ProxyException.Kind.PROXY_CLOSED);
		readByte(in, p, ProxyException.Kind.PROXY_CLOSED); // RSV
		int atyp = readByte(in, p, ProxyException.Kind.PROXY_CLOSED);
		if (rver != 5) throw new ProxyException(ProxyException.Kind.NOT_SOCKS5, p.host, p.port);
		if (rep != 0) {
			throw new ProxyException(ProxyException.Kind.SOCKS_REPLY, host, port, ProxyException.socksReplyText(rep));
		}
		int skip;
		switch (atyp) {
			case 1:
				skip = 4;
				break;
			case 4:
				skip = 16;
				break;
			case 3:
				skip = readByte(in, p, ProxyException.Kind.PROXY_CLOSED);
				break;
			default:
				throw new ProxyException(ProxyException.Kind.NOT_SOCKS5, p.host, p.port);
		}
		readFully(in, new byte[skip + 2], p); // BND.ADDR + BND.PORT
	}

	// ------------------------------------------------------------------ HTTP CONNECT

	/** {@code CONNECT host:port} on an already connected stream pair; expects a 2xx answer. */
	static void httpConnect(InputStream in, OutputStream out, ProxyConfig p, String host, int port)
			throws IOException {
		String authority = hostForHeader(host) + ":" + port;
		StringBuilder sb = new StringBuilder();
		sb.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
		sb.append("Host: ").append(authority).append("\r\n");
		String auth = basicAuth(p);
		if (auth != null) sb.append("Proxy-Authorization: ").append(auth).append("\r\n");
		sb.append("Proxy-Connection: Keep-Alive\r\n");
		sb.append("User-Agent: J2ME-Slot-Runner\r\n\r\n");
		out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.flush();

		String status = readLine(in);
		if (status == null) throw new ProxyException(ProxyException.Kind.PROXY_CLOSED, p.host, p.port);
		int code = parseStatusCode(status);
		if (code < 0) throw new ProxyException(ProxyException.Kind.NOT_HTTP, p.host, p.port);
		// Drain the response headers (byte by byte: the tunnel starts right after them)
		String line;
		long contentLength = 0;
		while ((line = readLine(in)) != null && !line.isEmpty()) {
			int c = line.indexOf(':');
			if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
				try {
					contentLength = Long.parseLong(line.substring(c + 1).trim());
				} catch (NumberFormatException ignored) {
				}
			}
		}
		if (code == 407) {
			throw new ProxyException(p.hasCredentials() ? ProxyException.Kind.AUTH_FAILED
					: ProxyException.Kind.AUTH_REQUIRED, p.host, p.port);
		}
		if (code < 200 || code > 299) {
			throw new ProxyException(ProxyException.Kind.CONNECT_FAILED, host, port, status.trim());
		}
		if (contentLength > 0 && contentLength < 65536) {
			readFully(in, new byte[(int) contentLength], p);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** "Basic base64(user:pass)" or null without credentials. */
	@Nullable
	public static String basicAuth(ProxyConfig p) {
		if (!p.hasCredentials()) return null;
		byte[] raw = (p.user + ":" + p.password).getBytes(StandardCharsets.UTF_8);
		return "Basic " + base64(raw);
	}

	static String hostForHeader(String host) {
		return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
	}

	/** Returns the status code of "HTTP/1.x NNN reason", or -1 when the line is not HTTP. */
	static int parseStatusCode(String statusLine) {
		if (statusLine == null || !statusLine.startsWith("HTTP/")) return -1;
		int sp = statusLine.indexOf(' ');
		if (sp < 0 || statusLine.length() < sp + 4) return -1;
		try {
			return Integer.parseInt(statusLine.substring(sp + 1, sp + 4));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Reads a CRLF (or LF) terminated ISO-8859-1 line without buffering ahead. Null on EOF. */
	@Nullable
	static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') {
				int len = sb.length();
				if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
				return sb.toString();
			}
			sb.append((char) b);
			if (sb.length() > MAX_LINE) throw new IOException("HTTP line too long");
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static int readByte(InputStream in, ProxyConfig p, ProxyException.Kind onEof) throws IOException {
		int b = in.read();
		if (b < 0) throw new ProxyException(onEof, p.host, p.port);
		return b;
	}

	private static void readFully(InputStream in, byte[] buf, ProxyConfig p) throws IOException {
		int off = 0;
		while (off < buf.length) {
			int r = in.read(buf, off, buf.length - off);
			if (r < 0) throw new ProxyException(ProxyException.Kind.PROXY_CLOSED, new EOFException(), p.host, p.port);
			off += r;
		}
	}

	private static final char[] B64 =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

	/** Standard Base64 with padding (android.util.Base64 is not available in JVM tests). */
	static String base64(byte[] data) {
		StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
		for (int i = 0; i < data.length; i += 3) {
			int b0 = data[i] & 0xFF;
			int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
			int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
			sb.append(B64[b0 >> 2]);
			sb.append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
			sb.append(i + 1 < data.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
			sb.append(i + 2 < data.length ? B64[b2 & 63] : '=');
		}
		return sb.toString();
	}

	static void closeQuietly(@Nullable Socket s) {
		if (s == null) return;
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}
}
