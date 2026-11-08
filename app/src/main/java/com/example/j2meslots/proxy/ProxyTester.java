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
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

import javax.net.ssl.SSLException;

/** "Test connection" button: one real HTTP(S) request through the proxy, timed. */
public final class ProxyTester {

	public static final class Result {
		public final boolean ok;
		public final long millis;
		public final int httpCode;
		/** Error text (already localized for {@link ProxyException}); null when ok. */
		public final String error;
		public final ProxyException.Kind kind;

		Result(boolean ok, long millis, int httpCode, String error, ProxyException.Kind kind) {
			this.ok = ok;
			this.millis = millis;
			this.httpCode = httpCode;
			this.error = error;
			this.kind = kind;
		}
	}

	private ProxyTester() {
	}

	@WorkerThread
	@NonNull
	public static Result test(@NonNull ProxyConfig proxy, @NonNull String testUrl, boolean trustAll) {
		URL url;
		try {
			url = new URL(testUrl.trim());
			String proto = url.getProtocol();
			if (!"http".equals(proto) && !"https".equals(proto)) throw new MalformedURLException(proto);
		} catch (MalformedURLException e) {
			return new Result(false, 0, -1, "URL: " + e.getMessage(), null);
		}
		long start = System.nanoTime();
		ProxiedHttpURLConnection c = new ProxiedHttpURLConnection(url, proxy, trustAll, false);
		c.setConnectTimeout(15000);
		c.setReadTimeout(15000);
		c.setRequestProperty("Accept-Encoding", "identity");
		try {
			int code = c.getResponseCode();
			long ms = (System.nanoTime() - start) / 1_000_000L;
			InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
			if (body != null) body.close();
			return new Result(true, ms, code, null, null);
		} catch (ProxyException e) {
			return new Result(false, 0, -1, e.getMessage(), e.kind);
		} catch (SSLException e) {
			return new Result(false, 0, -1, "TLS: " + e.getMessage(), ProxyException.Kind.TLS_FAILED);
		} catch (IOException | RuntimeException e) {
			return new Result(false, 0, -1, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
		} finally {
			c.disconnect();
		}
	}
}
