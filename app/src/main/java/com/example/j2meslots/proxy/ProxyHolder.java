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

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;

import javax.microedition.util.ContextHolder;

/**
 * Process-wide proxy of a slot process, loaded by {@link com.example.j2meslots.SlotRuntime}
 * from the launch Intent. Read by the GCF connection implementations (http, https, socket, ssl).
 */
public final class ProxyHolder {
	private static volatile ProxyConfig config;
	private static long lastToast;

	private ProxyHolder() {
	}

	public static void set(@Nullable ProxyConfig c) {
		config = c;
	}

	@Nullable
	public static ProxyConfig get() {
		return config;
	}

	public static boolean active() {
		return config != null;
	}

	/** Proxy to use for {@code host}, or null for a direct connection (no proxy / bypassed). */
	@Nullable
	public static ProxyConfig forHost(@Nullable String host) {
		ProxyConfig c = config;
		if (c == null || c.isBypassed(host)) return null;
		return c;
	}

	/** Redacted description for logs. */
	public static String describe() {
		ProxyConfig c = config;
		return c == null ? "off" : c.toString();
	}

	/**
	 * Shows a proxy failure to the player (games usually only print "connection error").
	 * Rate limited to one toast every 8 s.
	 */
	public static void report(ProxyException e) {
		long now = SystemClock.elapsedRealtime();
		synchronized (ProxyHolder.class) {
			if (now - lastToast < 8000) return;
			lastToast = now;
		}
		final String msg = e.getMessage();
		new Handler(Looper.getMainLooper()).post(() -> {
			try {
				Toast.makeText(ContextHolder.getAppContext(), msg, Toast.LENGTH_LONG).show();
			} catch (RuntimeException ignored) {
			}
		});
	}
}
