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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.microedition.io.HttpConnection;

/**
 * End-to-end tests of the proxy client against local fake servers (no network needed).
 * "game.invalid" cannot be resolved locally, so a successful request proves that DNS was
 * resolved by the proxy.
 */
public class ProxyConnectionTest {
	private FakeServers.Origin origin;
	private FakeServers.Server proxy;

	@After
	public void tearDown() throws IOException {
		ProxyHolder.set(null);
		if (proxy != null) proxy.close();
		if (origin != null) origin.close();
	}

	/** Server threads log asynchronously; wait a little for the entry. */
	private static boolean logged(FakeServers.Server server, String entry) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (server.log.contains(entry)) return true;
			Thread.sleep(20);
		}
		return false;
	}

	private static String read(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[256];
		int r;
		while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
		in.close();
		return out.toString("UTF-8");
	}

	private ProxiedHttpURLConnection open(ProxyConfig c, String path) throws IOException {
		URL url = new URL("http://" + FakeServers.GAME_HOST + ":8080" + path);
		ProxiedHttpURLConnection conn = new ProxiedHttpURLConnection(url, c, false, false);
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(5000);
		return conn;
	}

	@Test
	public void socks5WithoutAuthResolvesDnsRemotely() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.Socks5(null, null, origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "", "", "");
		ProxiedHttpURLConnection conn = open(c, "/hello?x=1");
		assertEquals(200, conn.getResponseCode());
		assertEquals("Hello, world", read(conn.getInputStream()));
		assertEquals("last", conn.getHeaderField("x-test"));
		assertNull(conn.getHeaderFieldKey(0));
		assertTrue(conn.getHeaderField(0).startsWith("HTTP/1.1 200"));
		assertTrue(logged(proxy, "CONNECT atyp=3 game.invalid:8080 cmd=1"));
		assertEquals("GET /hello?x=1 HTTP/1.1", origin.log.get(0));
		assertTrue(origin.log.get(1).contains("Host: game.invalid:8080"));
		assertTrue(origin.log.get(1).contains("Connection: close"));
	}

	@Test
	public void socks5WithPassword() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.Socks5("alice", "s3cret", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "alice", "s3cret", "");
		ProxiedHttpURLConnection conn = open(c, "/hello");
		assertEquals("Hello, world", read(conn.getInputStream()));
		assertTrue(logged(proxy, "AUTH_OK"));
	}

	@Test
	public void socks5WrongPasswordIsReported() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.Socks5("alice", "s3cret", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "alice", "wrong", "");
		try {
			open(c, "/hello").getResponseCode();
			fail();
		} catch (ProxyException e) {
			assertEquals(ProxyException.Kind.AUTH_FAILED, e.kind);
		}
		assertTrue(logged(proxy, "AUTH_FAIL"));
		assertTrue(origin.log.isEmpty());
	}

	@Test
	public void socks5MissingCredentials() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.Socks5("alice", "s3cret", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "", "", "");
		try {
			open(c, "/hello").getResponseCode();
			fail();
		} catch (ProxyException e) {
			assertEquals(ProxyException.Kind.AUTH_REQUIRED, e.kind);
		}
	}

	@Test
	public void httpForwardProxyUsesAbsoluteFormAndBasicAuth() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.HttpProxy("bob", "pw", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), "bob", "pw", "");
		ProxiedHttpURLConnection conn = open(c, "/hello");
		assertEquals(200, conn.getResponseCode());
		assertEquals("Hello, world", read(conn.getInputStream()));
		assertEquals("GET http://game.invalid:8080/hello HTTP/1.1", proxy.log.get(0));
	}

	@Test
	public void httpForwardProxyWrongPassword() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.HttpProxy("bob", "pw", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), "bob", "bad", "");
		try {
			open(c, "/hello").getInputStream();
			fail();
		} catch (ProxyException e) {
			assertEquals(ProxyException.Kind.AUTH_FAILED, e.kind);
		}
		assertTrue(logged(proxy, "407"));
	}

	@Test
	public void connectTunnelForPlainHttp() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.HttpProxy("bob", "pw", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.HTTPS_CONNECT, "127.0.0.1", proxy.port(), "bob", "pw", "");
		ProxiedHttpURLConnection conn = open(c, "/hello");
		assertEquals("Hello, world", read(conn.getInputStream()));
		assertEquals("CONNECT game.invalid:8080 HTTP/1.1", proxy.log.get(0));
		assertEquals("GET /hello HTTP/1.1", origin.log.get(0));
	}

	@Test
	public void connectTunnelWrongPassword() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.HttpProxy("bob", "pw", origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.HTTPS_CONNECT, "127.0.0.1", proxy.port(), "bob", "nope", "");
		try {
			open(c, "/hello").connect();
			fail();
		} catch (ProxyException e) {
			assertEquals(ProxyException.Kind.AUTH_FAILED, e.kind);
		}
	}

	@Test
	public void postBodyAndErrorStatus() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.Socks5(null, null, origin.port());
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "", "", "");

		ProxiedHttpURLConnection post = open(c, "/submit");
		post.setDoOutput(true);
		post.setRequestMethod("POST");
		post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		OutputStream os = post.getOutputStream();
		os.write("score=42".getBytes(StandardCharsets.UTF_8));
		os.close();
		assertEquals(200, post.getResponseCode());
		assertEquals("got:score=42", read(post.getInputStream()));
		assertTrue(origin.log.get(1).contains("Content-Length: 8"));

		ProxiedHttpURLConnection missing = open(c, "/missing");
		assertEquals(404, missing.getResponseCode());
		assertEquals("Not Found", missing.getResponseMessage());
		try {
			missing.getInputStream();
			fail();
		} catch (FileNotFoundException expected) {
			// HttpURLConnection semantics
		}
		assertNotNull(missing.getErrorStream());
		assertEquals("nope", read(missing.getErrorStream()));
	}

	@Test
	public void proxyRefusedIsReported() throws Exception {
		int freePort;
		try (ServerSocket s = new ServerSocket(0)) {
			freePort = s.getLocalPort();
		}
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", freePort, "", "", "");
		try {
			open(c, "/hello").getResponseCode();
			fail();
		} catch (ProxyException e) {
			assertEquals(ProxyException.Kind.PROXY_REFUSED, e.kind);
		}
	}

	@Test
	public void rawSocketThroughSocks5AndConnect() throws Exception {
		try (FakeServers.Echo echo = new FakeServers.Echo()) {
			proxy = new FakeServers.Socks5("u", "p", echo.port());
			ProxyConfig socks = new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "u", "p", "");
			try (Socket s = ProxySockets.openTunnel(socks, "chat.invalid", 7000, 5000)) {
				s.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
				byte[] b = new byte[4];
				assertEquals(4, s.getInputStream().read(b));
				assertEquals("ping", new String(b, StandardCharsets.US_ASCII));
			}
			assertTrue(logged(proxy, "CONNECT atyp=3 chat.invalid:7000 cmd=1"));
			proxy.close();

			proxy = new FakeServers.HttpProxy(null, null, echo.port());
			ProxyConfig http = new ProxyConfig(ProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), "", "", "");
			try (Socket s = ProxySockets.openTunnel(http, "chat.invalid", 7000, 5000)) {
				s.getOutputStream().write("pong".getBytes(StandardCharsets.US_ASCII));
				byte[] b = new byte[4];
				assertEquals(4, s.getInputStream().read(b));
				assertEquals("pong", new String(b, StandardCharsets.US_ASCII));
			}
			assertEquals("CONNECT chat.invalid:7000 HTTP/1.1", proxy.log.get(0));
		}
	}

	/** The J2ME GCF HttpConnection of a slot goes through the slot's proxy. */
	@Test
	public void j2meHttpConnectionUsesSlotProxy() throws Exception {
		origin = new FakeServers.Origin();
		proxy = new FakeServers.Socks5("alice", "s3cret", origin.port());
		ProxyHolder.set(new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", proxy.port(), "alice", "s3cret", ""));
		org.microemu.cldc.http.Connection gcf = new org.microemu.cldc.http.Connection();
		HttpConnection conn = (HttpConnection) gcf.openConnection(
				"http://" + FakeServers.GAME_HOST + ":8080/hello", 3, false);
		assertEquals(200, conn.getResponseCode());
		assertEquals("Hello, world", read(conn.openInputStream()));
		// J2ME header indexes skip the status line
		assertEquals("chunked", conn.getHeaderField(0));
		assertEquals("X-Test", conn.getHeaderFieldKey(1));
		conn.close();
		assertTrue(logged(proxy, "CONNECT atyp=3 game.invalid:8080 cmd=1"));
		assertTrue(origin.log.get(1).contains("Accept-Encoding: identity"));
	}

	@Test
	public void bypassedHostIsDirect() {
		ProxyHolder.set(new ProxyConfig(ProxyConfig.Type.SOCKS5, "127.0.0.1", 1, "", "", "*.local.test"));
		assertNull(ProxyHolder.forHost("a.local.test"));
		assertNull(ProxyHolder.forHost("127.0.0.1"));
		assertNotNull(ProxyHolder.forHost("game.example"));
	}
}
