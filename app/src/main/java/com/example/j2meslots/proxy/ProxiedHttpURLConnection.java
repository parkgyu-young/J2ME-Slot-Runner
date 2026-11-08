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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Minimal HTTP/1.1 client exposing the {@link java.net.HttpURLConnection} API, used by the
 * J2ME {@code http://} and {@code https://} connections when the slot has a proxy.
 * <ul>
 *     <li>HTTP proxy + http URL: request in absolute-form with {@code Proxy-Authorization}.</li>
 *     <li>HTTP proxy + https URL, HTTPS-CONNECT, SOCKS5: tunnel ({@link ProxySockets}) then
 *     origin-form request; https gets TLS on top of the tunnel ({@link LegacyTls}).</li>
 *     <li>One request per connection ({@code Connection: close}), no redirects (J2ME semantics),
 *     request body buffered in memory and sent with the request.</li>
 * </ul>
 */
public class ProxiedHttpURLConnection extends HttpsURLConnection {
	private final ProxyConfig proxy;
	private final boolean secure;
	private final boolean trustAll;
	private final boolean reportErrors;
	private final String host;
	private final int port;
	private final List<String[]> requestHeaders = new ArrayList<>();
	private final List<String[]> responseHeaders = new ArrayList<>();

	private Socket socket;
	private SSLSession sslSession;
	private OutputStream socketOut;
	private InputStream socketIn;
	private ByteArrayOutputStream requestBody;
	private boolean responseRead;
	private IOException failure;
	private int code = -1;
	private String message;
	private InputStream body;

	public ProxiedHttpURLConnection(@NonNull URL url, @NonNull ProxyConfig proxy, boolean trustAll) {
		this(url, proxy, trustAll, true);
	}

	public ProxiedHttpURLConnection(@NonNull URL url, @NonNull ProxyConfig proxy, boolean trustAll,
									boolean reportErrors) {
		super(url);
		this.proxy = proxy;
		this.secure = "https".equalsIgnoreCase(url.getProtocol());
		this.trustAll = trustAll;
		this.reportErrors = reportErrors;
		this.host = url.getHost();
		this.port = url.getPort() != -1 ? url.getPort() : (secure ? 443 : 80);
	}

	private boolean forwardMode() {
		return !secure && proxy.type == ProxyConfig.Type.HTTP;
	}

	// ------------------------------------------------------------------ connection

	@Override
	public void connect() throws IOException {
		if (connected) return;
		if (failure != null) throw failure;
		int timeout = getConnectTimeout() > 0 ? getConnectTimeout() : ProxySockets.CONNECT_TIMEOUT;
		try {
			Socket s;
			if (forwardMode()) {
				s = ProxySockets.connectToProxy(proxy, timeout);
			} else {
				s = ProxySockets.openTunnel(proxy, host, port, timeout);
				if (secure) {
					SSLSocket ssl = LegacyTls.wrap(s, host, port, trustAll);
					sslSession = ssl.getSession();
					s = ssl;
				}
			}
			s.setSoTimeout(getReadTimeout());
			socket = s;
			socketOut = new BufferedOutputStream(s.getOutputStream());
			socketIn = new BufferedInputStream(s.getInputStream());
			connected = true;
		} catch (IOException e) {
			fail(e);
			throw e;
		}
	}

	private void fail(IOException e) {
		failure = e;
		if (reportErrors && e instanceof ProxyException) {
			ProxyHolder.report((ProxyException) e);
		}
		disconnect();
	}

	@Override
	public void disconnect() {
		ProxySockets.closeQuietly(socket);
	}

	@Override
	public boolean usingProxy() {
		return true;
	}

	// ------------------------------------------------------------------ request

	@Override
	public void setRequestProperty(String key, String value) {
		if (connected) throw new IllegalStateException("Already connected");
		if (key == null) throw new NullPointerException("key is null");
		for (int i = requestHeaders.size() - 1; i >= 0; i--) {
			if (requestHeaders.get(i)[0].equalsIgnoreCase(key)) requestHeaders.remove(i);
		}
		requestHeaders.add(new String[]{key, value == null ? "" : value});
	}

	@Override
	public void addRequestProperty(String key, String value) {
		if (connected) throw new IllegalStateException("Already connected");
		if (key == null) throw new NullPointerException("key is null");
		requestHeaders.add(new String[]{key, value == null ? "" : value});
	}

	@Override
	public String getRequestProperty(String key) {
		if (key == null) return null;
		for (String[] h : requestHeaders) {
			if (h[0].equalsIgnoreCase(key)) return h[1];
		}
		return null;
	}

	@Override
	public Map<String, List<String>> getRequestProperties() {
		if (connected) throw new IllegalStateException("Already connected");
		return toMap(requestHeaders);
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		if (responseRead) throw new ProtocolException("Cannot write output after reading input");
		if (!doOutput) {
			throw new ProtocolException("cannot write to a URLConnection if doOutput=false - call setDoOutput(true)");
		}
		if ("GET".equals(method)) method = "POST";
		if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)
				&& !"PATCH".equals(method)) {
			throw new ProtocolException("HTTP method " + method + " doesn't support output");
		}
		connect();
		if (requestBody == null) requestBody = new ByteArrayOutputStream();
		return requestBody;
	}

	private void sendRequest() throws IOException {
		connect();
		String target;
		if (forwardMode()) {
			String ext = url.toExternalForm();
			int hash = ext.indexOf('#');
			target = hash >= 0 ? ext.substring(0, hash) : ext;
		} else {
			target = url.getFile();
			if (target == null || target.isEmpty()) target = "/";
		}
		StringBuilder sb = new StringBuilder(128);
		sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
		if (getRequestProperty("Host") == null) {
			sb.append("Host: ").append(ProxySockets.hostForHeader(host));
			if (port != (secure ? 443 : 80)) sb.append(':').append(port);
			sb.append("\r\n");
		}
		for (String[] h : requestHeaders) {
			String n = h[0].toLowerCase(Locale.ROOT);
			if (n.equals("connection") || n.equals("proxy-connection") || n.equals("content-length")
					|| n.equals("transfer-encoding") || n.equals("proxy-authorization")) {
				continue;
			}
			sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
		}
		if (getRequestProperty("User-Agent") == null) {
			String agent = System.getProperty("http.agent");
			if (agent != null && !agent.isEmpty()) sb.append("User-Agent: ").append(agent).append("\r\n");
		}
		if (forwardMode()) {
			String auth = ProxySockets.basicAuth(proxy);
			if (auth != null) sb.append("Proxy-Authorization: ").append(auth).append("\r\n");
		}
		byte[] bodyBytes = requestBody != null ? requestBody.toByteArray() : null;
		if (bodyBytes != null || "POST".equals(method) || "PUT".equals(method)) {
			sb.append("Content-Length: ").append(bodyBytes == null ? 0 : bodyBytes.length).append("\r\n");
		}
		sb.append("Connection: close\r\n\r\n");
		socketOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		if (bodyBytes != null && bodyBytes.length > 0) socketOut.write(bodyBytes);
		socketOut.flush();
	}

	// ------------------------------------------------------------------ response

	private void readResponse() throws IOException {
		if (responseRead) {
			if (failure != null) throw failure;
			return;
		}
		responseRead = true;
		try {
			sendRequest();
			List<String[]> headers = new ArrayList<>();
			String status;
			do {
				headers.clear();
				status = ProxySockets.readLine(socketIn);
				if (status == null) throw new IOException("Connection closed: no HTTP response from " + host);
				code = ProxySockets.parseStatusCode(status);
				if (code < 0) {
					if (forwardMode()) throw new ProxyException(ProxyException.Kind.NOT_HTTP, proxy.host, proxy.port);
					throw new ProtocolException("Unexpected status line: " + status);
				}
				int sp2 = status.indexOf(' ', status.indexOf(' ') + 1);
				message = sp2 > 0 ? status.substring(sp2 + 1) : "";
				String line;
				while ((line = ProxySockets.readLine(socketIn)) != null && !line.isEmpty()) {
					int c = line.indexOf(':');
					if (c <= 0) continue;
					headers.add(new String[]{line.substring(0, c).trim(), line.substring(c + 1).trim()});
				}
			} while (code >= 100 && code < 200 && code != 101);
			responseHeaders.add(new String[]{null, status});
			responseHeaders.addAll(headers);
			if (code == 407 && forwardMode()) {
				throw new ProxyException(proxy.hasCredentials() ? ProxyException.Kind.AUTH_FAILED
						: ProxyException.Kind.AUTH_REQUIRED, proxy.host, proxy.port);
			}
			body = new SocketClosingStream(bodyStream());
		} catch (IOException e) {
			fail(e);
			throw e;
		}
	}

	private InputStream bodyStream() {
		if ("HEAD".equals(method) || code == 204 || code == 304 || (code >= 100 && code < 200)) {
			return new ByteArrayInputStream(new byte[0]);
		}
		String te = getHeaderField("Transfer-Encoding");
		if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
			return new ChunkedInputStream(socketIn);
		}
		String cl = getHeaderField("Content-Length");
		if (cl != null) {
			try {
				long len = Long.parseLong(cl.trim());
				if (len >= 0) return new FixedLengthInputStream(socketIn, len);
			} catch (NumberFormatException ignored) {
			}
		}
		return socketIn; // until the server closes (Connection: close)
	}

	@Override
	public InputStream getInputStream() throws IOException {
		if (!doInput) throw new ProtocolException("cannot read from a URLConnection if doInput=false");
		readResponse();
		if (code >= 400) {
			if (code == 404 || code == 410) throw new FileNotFoundException(url.toString());
			throw new IOException("Server returned HTTP response code: " + code + " for URL: " + url);
		}
		return body;
	}

	@Override
	public InputStream getErrorStream() {
		return responseRead && failure == null && code >= 400 ? body : null;
	}

	@Override
	public int getResponseCode() throws IOException {
		readResponse();
		return code;
	}

	@Override
	public String getResponseMessage() throws IOException {
		readResponse();
		return message;
	}

	private boolean ensureHeaders() {
		try {
			readResponse();
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public String getHeaderField(int n) {
		if (!ensureHeaders() || n < 0 || n >= responseHeaders.size()) return null;
		return responseHeaders.get(n)[1];
	}

	@Override
	public String getHeaderFieldKey(int n) {
		if (!ensureHeaders() || n < 0 || n >= responseHeaders.size()) return null;
		return responseHeaders.get(n)[0];
	}

	@Override
	public String getHeaderField(String name) {
		if (!ensureHeaders()) return null;
		if (name == null) return responseHeaders.isEmpty() ? null : responseHeaders.get(0)[1];
		for (int i = responseHeaders.size() - 1; i >= 1; i--) {
			String[] h = responseHeaders.get(i);
			if (name.equalsIgnoreCase(h[0])) return h[1];
		}
		return null;
	}

	@Override
	public Map<String, List<String>> getHeaderFields() {
		if (!ensureHeaders()) return Collections.emptyMap();
		return toMap(responseHeaders);
	}

	private static Map<String, List<String>> toMap(List<String[]> list) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		for (String[] h : list) {
			List<String> values = map.get(h[0]);
			if (values == null) {
				values = new ArrayList<>();
				map.put(h[0], values);
			}
			values.add(h[1]);
		}
		for (Map.Entry<String, List<String>> e : map.entrySet()) {
			e.setValue(Collections.unmodifiableList(e.getValue()));
		}
		return Collections.unmodifiableMap(map);
	}

	// ------------------------------------------------------------------ HttpsURLConnection

	@Override
	public String getCipherSuite() {
		if (sslSession == null) throw new IllegalStateException("No TLS session");
		return sslSession.getCipherSuite();
	}

	@Override
	public Certificate[] getLocalCertificates() {
		if (sslSession == null) throw new IllegalStateException("No TLS session");
		return sslSession.getLocalCertificates();
	}

	@Override
	public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
		if (sslSession == null) throw new IllegalStateException("No TLS session");
		return sslSession.getPeerCertificates();
	}

	@Nullable
	public SSLSession getSslSession() {
		return sslSession;
	}

	// ------------------------------------------------------------------ body streams

	private final class SocketClosingStream extends FilterInputStream {
		SocketClosingStream(InputStream in) {
			super(in);
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				disconnect();
			}
		}
	}

	static final class FixedLengthInputStream extends FilterInputStream {
		private long remaining;

		FixedLengthInputStream(InputStream in, long length) {
			super(in);
			remaining = length;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) return -1;
			int b = in.read();
			if (b >= 0) remaining--;
			return b;
		}

		@Override
		public int read(@NonNull byte[] b, int off, int len) throws IOException {
			if (remaining <= 0) return -1;
			int r = in.read(b, off, (int) Math.min(len, remaining));
			if (r > 0) remaining -= r;
			return r;
		}

		@Override
		public long skip(long n) throws IOException {
			long s = in.skip(Math.min(n, remaining));
			remaining -= s;
			return s;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(in.available(), remaining);
		}

		@Override
		public boolean markSupported() {
			return false;
		}
	}

	static final class ChunkedInputStream extends FilterInputStream {
		private long chunkRemaining;
		private boolean eof;

		ChunkedInputStream(InputStream in) {
			super(in);
		}

		private boolean nextChunk() throws IOException {
			if (eof) return false;
			if (chunkRemaining > 0) return true;
			String line = ProxySockets.readLine(in);
			if (line != null && line.isEmpty()) line = ProxySockets.readLine(in); // CRLF after data
			if (line == null) throw new IOException("Unexpected end of chunked stream");
			int semi = line.indexOf(';');
			String size = (semi >= 0 ? line.substring(0, semi) : line).trim();
			try {
				chunkRemaining = Long.parseLong(size, 16);
			} catch (NumberFormatException e) {
				throw new IOException("Bad chunk size: " + line);
			}
			if (chunkRemaining == 0) {
				String trailer;
				while ((trailer = ProxySockets.readLine(in)) != null && !trailer.isEmpty()) {
					// ignore trailers
				}
				eof = true;
				return false;
			}
			return true;
		}

		@Override
		public int read() throws IOException {
			if (!nextChunk()) return -1;
			int b = in.read();
			if (b < 0) throw new IOException("Unexpected end of chunk");
			chunkRemaining--;
			return b;
		}

		@Override
		public int read(@NonNull byte[] b, int off, int len) throws IOException {
			if (len == 0) return 0;
			if (!nextChunk()) return -1;
			int r = in.read(b, off, (int) Math.min(len, chunkRemaining));
			if (r < 0) throw new IOException("Unexpected end of chunk");
			chunkRemaining -= r;
			return r;
		}

		@Override
		public int available() throws IOException {
			if (eof) return 0;
			return (int) Math.min(in.available(), chunkRemaining);
		}

		@Override
		public boolean markSupported() {
			return false;
		}
	}
}
