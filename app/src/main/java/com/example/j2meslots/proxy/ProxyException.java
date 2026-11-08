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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.io.IOException;

import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.R;

/**
 * Proxy failure with a precise, localized reason (shown in the Test button and as a toast in
 * the slot). Messages never include the password.
 */
public class ProxyException extends IOException {

	public enum Kind {
		PROXY_DNS(R.string.proxy_err_dns),
		PROXY_REFUSED(R.string.proxy_err_refused),
		PROXY_TIMEOUT(R.string.proxy_err_timeout),
		PROXY_CLOSED(R.string.proxy_err_closed),
		NOT_SOCKS5(R.string.proxy_err_not_socks5),
		NOT_HTTP(R.string.proxy_err_not_http),
		AUTH_REQUIRED(R.string.proxy_err_auth_required),
		AUTH_FAILED(R.string.proxy_err_auth_failed),
		AUTH_UNSUPPORTED(R.string.proxy_err_auth_unsupported),
		SOCKS_REPLY(R.string.proxy_err_socks_reply),
		CONNECT_FAILED(R.string.proxy_err_connect_failed),
		TLS_FAILED(R.string.proxy_err_tls),
		BAD_RESPONSE(R.string.proxy_err_bad_response);

		@StringRes
		public final int message;

		Kind(@StringRes int message) {
			this.message = message;
		}
	}

	public final Kind kind;

	public ProxyException(@NonNull Kind kind, Object... args) {
		super(format(kind, args));
		this.kind = kind;
	}

	public ProxyException(@NonNull Kind kind, Throwable cause, Object... args) {
		super(format(kind, args), cause);
		this.kind = kind;
	}

	private static String format(Kind kind, Object... args) {
		Context ctx = null;
		try {
			ctx = ContextHolder.getAppContext();
		} catch (RuntimeException ignored) {
		}
		if (ctx == null) {
			// JVM unit tests: no resources
			StringBuilder sb = new StringBuilder("Proxy error: ").append(kind.name());
			for (Object a : args) sb.append(' ').append(a);
			return sb.toString();
		}
		return ctx.getString(kind.message, args);
	}

	/** SOCKS5 reply codes (RFC 1928 §6) to readable text. */
	public static String socksReplyText(int rep) {
		switch (rep) {
			case 1: return "general SOCKS server failure";
			case 2: return "connection not allowed by ruleset";
			case 3: return "network unreachable";
			case 4: return "host unreachable";
			case 5: return "connection refused";
			case 6: return "TTL expired";
			case 7: return "command not supported";
			case 8: return "address type not supported";
			default: return "code " + rep;
		}
	}
}
