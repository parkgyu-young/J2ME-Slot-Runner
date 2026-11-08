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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProxyConfigTest {

	@Test
	public void parseSocks5WithCredentials() {
		ProxyConfig c = ProxyConfig.parseUri("socks5://alice:s3cr%3At@10.1.2.3:1080", "");
		assertEquals(ProxyConfig.Type.SOCKS5, c.type);
		assertEquals("10.1.2.3", c.host);
		assertEquals(1080, c.port);
		assertEquals("alice", c.user);
		assertEquals("s3cr:t", c.password);
	}

	@Test
	public void parseHttpAndConnectSchemes() {
		assertEquals(ProxyConfig.Type.HTTP, ProxyConfig.parseUri("http://p.example:3128", null).type);
		assertEquals(ProxyConfig.Type.HTTPS_CONNECT, ProxyConfig.parseUri("https-connect://p:443", null).type);
		assertEquals(ProxyConfig.Type.SOCKS5, ProxyConfig.parseUri("socks5h://p:1", null).type);
	}

	@Test
	public void defaultPorts() {
		assertEquals(1080, ProxyConfig.parseUri("socks5://h", null).port);
		assertEquals(8080, ProxyConfig.parseUri("http://h", null).port);
	}

	@Test
	public void ipv6Host() {
		ProxyConfig c = ProxyConfig.parseUri("socks5://[2001:db8::1]:9050", null);
		assertEquals("2001:db8::1", c.host);
		assertEquals(9050, c.port);
		assertEquals("socks5://[2001:db8::1]:9050", c.toUri(true));
	}

	@Test
	public void roundTripKeepsSpecialCharacters() {
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.HTTP, "proxy.lan", 8080,
				"user@corp", "p@ss word:+/", "*.lan");
		String uri = c.toUri(true);
		ProxyConfig back = ProxyConfig.parseUri(uri, "*.lan");
		assertEquals(c, back);
		assertEquals("p@ss word:+/", back.password);
		assertFalse(c.toUri(false).contains("ss"));
	}

	@Test
	public void toStringNeverContainsPassword() {
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "h", 1, "u", "topsecret", "");
		assertFalse(c.toString().contains("topsecret"));
		assertFalse(c.toString().contains("u:"));
	}

	@Test
	public void invalidUris() {
		String[] bad = {"ftp://h:1", "h:1", "socks5://h:99999", "socks5://:1080", "socks5://h:abc"};
		for (String s : bad) {
			try {
				ProxyConfig.parseUri(s, null);
				fail("accepted " + s);
			} catch (IllegalArgumentException expected) {
				assertNotNull(expected.getMessage());
			}
		}
	}

	@Test
	public void bypassRules() {
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "p", 1080, null, null,
				"example.com, *.game.net\n.lan 10.0.0.0/8 192.168.1.7 fd00::/8");
		assertTrue(c.isBypassed("example.com"));
		assertFalse(c.isBypassed("www.example.com"));
		assertTrue(c.isBypassed("a.game.net"));
		assertTrue(c.isBypassed("game.net"));
		assertTrue(c.isBypassed("printer.lan"));
		assertTrue(c.isBypassed("10.20.30.40"));
		assertFalse(c.isBypassed("11.0.0.1"));
		assertTrue(c.isBypassed("192.168.1.7"));
		assertFalse(c.isBypassed("192.168.1.8"));
		assertTrue(c.isBypassed("fd12::5"));
		assertTrue(c.isBypassed("[fd12::5]"));
		assertFalse(c.isBypassed("2001:db8::1"));
		// loopback is always direct
		assertTrue(c.isBypassed("localhost"));
		assertTrue(c.isBypassed("127.0.0.1"));
		assertTrue(c.isBypassed("::1"));
		assertFalse(c.isBypassed("server.game.com"));
		assertFalse(c.isBypassed(null));
	}

	@Test
	public void cidrDoesNotResolveHostNames() {
		ProxyConfig c = new ProxyConfig(ProxyConfig.Type.SOCKS5, "p", 1080, null, null, "0.0.0.0/0");
		// host names never match CIDR rules (no DNS lookups)
		assertFalse(c.isBypassed("example.com"));
		assertTrue(c.isBypassed("8.8.8.8"));
	}

	@Test
	public void ipLiterals() {
		assertArrayEquals(new byte[]{10, 0, 0, 1}, IpUtils.parseLiteral("10.0.0.1"));
		assertNull(IpUtils.parseLiteral("10.0.0"));
		assertNull(IpUtils.parseLiteral("256.0.0.1"));
		assertNull(IpUtils.parseLiteral("example.com"));
		byte[] v6 = IpUtils.parseLiteral("::ffff:1.2.3.4");
		assertNotNull(v6);
		assertEquals(16, v6.length);
		assertEquals(4, v6[15]);
		assertTrue(IpUtils.cidrMatch("1.2.3.0/24", v6));
		assertNull(IpUtils.parseLiteral("1::2::3"));
	}

	@Test
	public void validate() {
		assertNull(new ProxyConfig(ProxyConfig.Type.HTTP, "h", 80, "", "", "").validate());
		assertEquals("port", new ProxyConfig(ProxyConfig.Type.HTTP, "h", 0, "", "", "").validate());
		assertEquals("host", new ProxyConfig(ProxyConfig.Type.HTTP, " ", 80, "", "", "").validate());
	}

	@Test
	public void base64() {
		assertEquals("dXNlcjpwYXNz", ProxySockets.base64("user:pass".getBytes()));
		assertEquals("YQ==", ProxySockets.base64("a".getBytes()));
		assertEquals("YWI=", ProxySockets.base64("ab".getBytes()));
		assertEquals("", ProxySockets.base64(new byte[0]));
	}
}
