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

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS helpers for J2ME connections (same idea as upstream's TLSSocketFactory):
 * <ul>
 *     <li>Always enables every protocol the device still supports, including TLS 1.0/1.1 that old
 *     game servers need.</li>
 *     <li>Certificates are validated with the system trust store <b>by default</b>. Only the
 *     per-slot opt-in "allow legacy certificates" accepts expired / self-signed / untrusted
 *     certificates (and skips host name verification).</li>
 * </ul>
 */
public final class LegacyTls {
	private static final String[] WANTED = {"TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1"};
	private static SSLSocketFactory strictFactory;
	private static SSLSocketFactory legacyFactory;

	private LegacyTls() {
	}

	/** Factory that enables legacy protocols; trusts everything only when {@code trustAll}. */
	public static synchronized SSLSocketFactory factory(boolean trustAll) throws IOException {
		if (trustAll) {
			if (legacyFactory == null) legacyFactory = new ProtocolSocketFactory(create(true));
			return legacyFactory;
		}
		if (strictFactory == null) strictFactory = new ProtocolSocketFactory(create(false));
		return strictFactory;
	}

	private static SSLSocketFactory create(boolean trustAll) throws IOException {
		try {
			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(null, trustAll ? new TrustManager[]{new TrustAllManager()} : null, null);
			return ctx.getSocketFactory();
		} catch (GeneralSecurityException e) {
			throw new IOException(e.toString(), e);
		}
	}

	/** Host name verifier matching {@link #factory(boolean)}. */
	@SuppressLint("BadHostnameVerifier")
	public static HostnameVerifier hostnameVerifier(boolean trustAll) {
		return trustAll ? (h, s) -> true : HttpsURLConnection.getDefaultHostnameVerifier();
	}

	/**
	 * Builds TLS on top of an already open (direct or proxied) socket, performs the handshake and
	 * verifies the host name unless {@code trustAll}.
	 */
	public static SSLSocket wrap(@NonNull Socket plain, @NonNull String host, int port, boolean trustAll)
			throws IOException {
		SSLSocket ssl = (SSLSocket) factory(trustAll).createSocket(plain, host, port, true);
		try {
			ssl.startHandshake();
			if (!trustAll && !hostnameVerifier(false).verify(host, ssl.getSession())) {
				throw new SSLPeerUnverifiedException("Certificate does not match host name " + host);
			}
			return ssl;
		} catch (IOException | RuntimeException e) {
			try {
				ssl.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
	}

	static Socket enableProtocols(Socket socket) {
		if (socket instanceof SSLSocket) {
			SSLSocket s = (SSLSocket) socket;
			List<String> supported = Arrays.asList(s.getSupportedProtocols());
			List<String> enabled = new ArrayList<>();
			for (String p : WANTED) {
				if (supported.contains(p)) enabled.add(p);
			}
			if (!enabled.isEmpty()) s.setEnabledProtocols(enabled.toArray(new String[0]));
		}
		return socket;
	}

	@SuppressLint("CustomX509TrustManager")
	private static final class TrustAllManager implements X509TrustManager {
		@SuppressLint("TrustAllX509TrustManager")
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		@SuppressLint("TrustAllX509TrustManager")
		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	private static final class ProtocolSocketFactory extends SSLSocketFactory {
		private final SSLSocketFactory delegate;

		ProtocolSocketFactory(SSLSocketFactory delegate) {
			this.delegate = delegate;
		}

		@Override
		public String[] getDefaultCipherSuites() {
			return delegate.getDefaultCipherSuites();
		}

		@Override
		public String[] getSupportedCipherSuites() {
			return delegate.getSupportedCipherSuites();
		}

		@Override
		public Socket createSocket() throws IOException {
			return enableProtocols(delegate.createSocket());
		}

		@Override
		public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
			return enableProtocols(delegate.createSocket(s, host, port, autoClose));
		}

		@Override
		public Socket createSocket(String host, int port) throws IOException {
			return enableProtocols(delegate.createSocket(host, port));
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
			return enableProtocols(delegate.createSocket(host, port, localHost, localPort));
		}

		@Override
		public Socket createSocket(InetAddress host, int port) throws IOException {
			return enableProtocols(delegate.createSocket(host, port));
		}

		@Override
		public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
			return enableProtocols(delegate.createSocket(address, port, localAddress, localPort));
		}
	}
}
