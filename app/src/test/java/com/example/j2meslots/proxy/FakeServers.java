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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tiny in-process servers for JVM tests: an origin HTTP server, a SOCKS5 proxy (with optional
 * RFC 1929 auth, resolving "game.invalid" itself = remote DNS) and an HTTP proxy (forward +
 * CONNECT, Basic auth). Every proxy log line is recorded so tests can assert traffic went through.
 */
final class FakeServers {
	static final String GAME_HOST = "game.invalid"; // never resolvable locally (RFC 6761)

	private FakeServers() {
	}

	abstract static class Server implements Closeable {
		final ServerSocket server;
		final List<String> log = Collections.synchronizedList(new ArrayList<>());
		private volatile boolean closed;

		Server() throws IOException {
			server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
			Thread t = new Thread(() -> {
				while (!closed) {
					try {
						Socket s = server.accept();
						new Thread(() -> {
							try {
								handle(s);
							} catch (IOException ignored) {
							} finally {
								try {
									s.close();
								} catch (IOException ignored) {
								}
							}
						}).start();
					} catch (IOException e) {
						return;
					}
				}
			}, getClass().getSimpleName());
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return server.getLocalPort();
		}

		abstract void handle(Socket s) throws IOException;

		@Override
		public void close() throws IOException {
			closed = true;
			server.close();
		}
	}

	/** Origin web server. GET /hello -> chunked "Hello, world"; /missing -> 404; echoes POST bodies. */
	static final class Origin extends Server {
		Origin() throws IOException {
			super();
		}

		@Override
		void handle(Socket s) throws IOException {
			InputStream in = s.getInputStream();
			String requestLine = ProxySockets.readLine(in);
			if (requestLine == null) return;
			int contentLength = 0;
			StringBuilder headers = new StringBuilder();
			String line;
			while ((line = ProxySockets.readLine(in)) != null && !line.isEmpty()) {
				headers.append(line).append('\n');
				if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
					contentLength = Integer.parseInt(line.substring(15).trim());
				}
			}
			byte[] body = new byte[contentLength];
			new DataInputStream(in).readFully(body);
			log.add(requestLine);
			log.add(headers.toString());
			log.add(new String(body, StandardCharsets.UTF_8));
			OutputStream out = s.getOutputStream();
			if (requestLine.contains("/missing")) {
				out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: 4\r\nX-Test: a\r\n\r\nnope")
						.getBytes(StandardCharsets.ISO_8859_1));
			} else if (requestLine.startsWith("POST")) {
				String reply = "got:" + new String(body, StandardCharsets.UTF_8);
				out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + reply.length() + "\r\n\r\n" + reply)
						.getBytes(StandardCharsets.ISO_8859_1));
			} else {
				out.write(("HTTP/1.1 100 Continue\r\n\r\n"
						+ "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nX-Test: first\r\nX-Test: last\r\n\r\n"
						+ "5\r\nHello\r\n7;ext=1\r\n, world\r\n0\r\nTrailer: x\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
			}
			out.flush();
		}
	}

	/** Echo server for raw socket tests. */
	static final class Echo extends Server {
		Echo() throws IOException {
			super();
		}

		@Override
		void handle(Socket s) throws IOException {
			byte[] buf = new byte[1024];
			int r;
			while ((r = s.getInputStream().read(buf)) > 0) {
				s.getOutputStream().write(buf, 0, r);
				s.getOutputStream().flush();
			}
		}
	}

	static void relay(Socket a, Socket b) {
		Thread t = new Thread(() -> pump(b, a));
		t.setDaemon(true);
		t.start();
		pump(a, b);
		try {
			t.join(5000);
		} catch (InterruptedException ignored) {
		}
	}

	private static void pump(Socket from, Socket to) {
		byte[] buf = new byte[8192];
		try {
			InputStream in = from.getInputStream();
			OutputStream out = to.getOutputStream();
			int r;
			while ((r = in.read(buf)) > 0) {
				out.write(buf, 0, r);
				out.flush();
			}
			to.shutdownOutput();
		} catch (IOException ignored) {
		}
	}

	/** SOCKS5 proxy; maps GAME_HOST (and any domain) to the given upstream port on 127.0.0.1. */
	static final class Socks5 extends Server {
		final String user;
		final String pass;
		final int upstreamPort;

		Socks5(String user, String pass, int upstreamPort) throws IOException {
			super();
			this.user = user;
			this.pass = pass;
			this.upstreamPort = upstreamPort;
		}

		@Override
		void handle(Socket s) throws IOException {
			DataInputStream in = new DataInputStream(s.getInputStream());
			OutputStream out = s.getOutputStream();
			if (in.readUnsignedByte() != 5) return;
			int n = in.readUnsignedByte();
			byte[] methods = new byte[n];
			in.readFully(methods);
			boolean offersAuth = false;
			boolean offersNone = false;
			for (byte m : methods) {
				if (m == 2) offersAuth = true;
				if (m == 0) offersNone = true;
			}
			if (user != null) {
				if (!offersAuth) {
					out.write(new byte[]{5, (byte) 0xFF});
					log.add("NO_ACCEPTABLE");
					return;
				}
				out.write(new byte[]{5, 2});
				in.readUnsignedByte();
				byte[] u = new byte[in.readUnsignedByte()];
				in.readFully(u);
				byte[] p = new byte[in.readUnsignedByte()];
				in.readFully(p);
				boolean ok = user.equals(new String(u, StandardCharsets.UTF_8))
						&& pass.equals(new String(p, StandardCharsets.UTF_8));
				out.write(new byte[]{1, (byte) (ok ? 0 : 1)});
				log.add(ok ? "AUTH_OK" : "AUTH_FAIL");
				if (!ok) return;
			} else {
				if (!offersNone) return;
				out.write(new byte[]{5, 0});
			}
			in.readUnsignedByte(); // ver
			int cmd = in.readUnsignedByte();
			in.readUnsignedByte();
			int atyp = in.readUnsignedByte();
			String host;
			if (atyp == 3) {
				byte[] name = new byte[in.readUnsignedByte()];
				in.readFully(name);
				host = new String(name, StandardCharsets.US_ASCII);
			} else {
				byte[] a = new byte[atyp == 1 ? 4 : 16];
				in.readFully(a);
				host = InetAddress.getByAddress(a).getHostAddress();
			}
			int port = in.readUnsignedShort();
			log.add("CONNECT atyp=" + atyp + " " + host + ":" + port + " cmd=" + cmd);
			Socket up = new Socket("127.0.0.1", upstreamPort);
			out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
			out.flush();
			relay(s, up);
			up.close();
		}
	}

	/** HTTP proxy: forward (absolute-form) and CONNECT, optional Basic auth. */
	static final class HttpProxy extends Server {
		final String expectedAuth;
		final int upstreamPort;

		HttpProxy(String user, String pass, int upstreamPort) throws IOException {
			super();
			this.expectedAuth = user == null ? null
					: "Basic " + ProxySockets.base64((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
			this.upstreamPort = upstreamPort;
		}

		@Override
		void handle(Socket s) throws IOException {
			InputStream in = s.getInputStream();
			OutputStream out = s.getOutputStream();
			String requestLine = ProxySockets.readLine(in);
			if (requestLine == null) return;
			String auth = null;
			int contentLength = 0;
			List<String> headers = new ArrayList<>();
			String line;
			while ((line = ProxySockets.readLine(in)) != null && !line.isEmpty()) {
				headers.add(line);
				String lower = line.toLowerCase(Locale.ROOT);
				if (lower.startsWith("proxy-authorization:")) auth = line.substring(20).trim();
				if (lower.startsWith("content-length:")) contentLength = Integer.parseInt(line.substring(15).trim());
			}
			log.add(requestLine);
			if (expectedAuth != null && !expectedAuth.equals(auth)) {
				log.add("407");
				out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"t\"\r\nContent-Length: 0\r\n\r\n"
						.getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
				return;
			}
			Socket up = new Socket("127.0.0.1", upstreamPort);
			if (requestLine.startsWith("CONNECT ")) {
				out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
				relay(s, up);
			} else {
				// forward: rewrite absolute-form to origin-form
				String[] parts = requestLine.split(" ");
				String target = parts[1];
				int pathStart = target.indexOf('/', target.indexOf("://") + 3);
				String path = pathStart < 0 ? "/" : target.substring(pathStart);
				ByteArrayOutputStream req = new ByteArrayOutputStream();
				req.write((parts[0] + " " + path + " " + parts[2] + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
				for (String h : headers) {
					if (!h.toLowerCase(Locale.ROOT).startsWith("proxy-")) {
						req.write((h + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
					}
				}
				req.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
				byte[] body = new byte[contentLength];
				new DataInputStream(in).readFully(body);
				req.write(body);
				up.getOutputStream().write(req.toByteArray());
				up.getOutputStream().flush();
				relay(s, up);
			}
			up.close();
		}
	}
}
