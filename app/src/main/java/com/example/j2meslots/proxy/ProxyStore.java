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
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Proxy settings of the launcher process. Everything (including passwords) is stored in
 * {@link EncryptedSharedPreferences} "proxy_secure". If the device keystore is broken the
 * settings fall back to plain prefs <b>without</b> the password (see {@link #isEncrypted()}).
 * <p>
 * Slot processes never open this store: the launcher passes the effective config in the Intent.
 */
public final class ProxyStore {
	private static final String TAG = "ProxyStore";
	public static final String DEFAULT_TEST_URL = "http://connectivitycheck.gstatic.com/generate_204";

	public enum Mode {OFF, GLOBAL, PER_SLOT}

	private static ProxyStore instance;
	private final SharedPreferences prefs;
	private final boolean encrypted;

	public static synchronized ProxyStore get(Context ctx) {
		if (instance == null) instance = new ProxyStore(ctx.getApplicationContext());
		return instance;
	}

	private ProxyStore(Context ctx) {
		SharedPreferences p;
		boolean enc;
		try {
			MasterKey key = new MasterKey.Builder(ctx)
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();
			p = EncryptedSharedPreferences.create(ctx, "proxy_secure", key,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
			enc = true;
		} catch (Exception | Error e) {
			Log.w(TAG, "EncryptedSharedPreferences unavailable, passwords will not be saved: " + e);
			p = ctx.getSharedPreferences("proxy_plain", Context.MODE_PRIVATE);
			enc = false;
		}
		prefs = p;
		encrypted = enc;
	}

	public boolean isEncrypted() {
		return encrypted;
	}

	public Mode mode() {
		try {
			return Mode.valueOf(prefs.getString("mode", Mode.OFF.name()));
		} catch (IllegalArgumentException e) {
			return Mode.OFF;
		}
	}

	public void setMode(Mode mode) {
		prefs.edit().putString("mode", mode.name()).apply();
	}

	@Nullable
	public ProxyConfig global() {
		return read("g_");
	}

	public void setGlobal(@Nullable ProxyConfig c) {
		write("g_", c);
	}

	@Nullable
	public ProxyConfig slot(int n) {
		return read("s" + n + "_");
	}

	public void setSlot(int n, @Nullable ProxyConfig c) {
		write("s" + n + "_", c);
	}

	public boolean slotEnabled(int n) {
		return prefs.getBoolean("s" + n + "_on", false);
	}

	public void setSlotEnabled(int n, boolean on) {
		prefs.edit().putBoolean("s" + n + "_on", on).apply();
	}

	public void removeSlot(int n) {
		write("s" + n + "_", null);
		prefs.edit().remove("s" + n + "_on").apply();
	}

	/** Proxy that slot {@code n} must use, or null for direct connections. */
	@Nullable
	public ProxyConfig effectiveFor(int n) {
		switch (mode()) {
			case GLOBAL:
				return global();
			case PER_SLOT:
				return slotEnabled(n) ? slot(n) : null;
			case OFF:
			default:
				return null;
		}
	}

	@NonNull
	public String testUrl() {
		String s = prefs.getString("test_url", null);
		return s == null || s.trim().isEmpty() ? DEFAULT_TEST_URL : s;
	}

	public void setTestUrl(String url) {
		prefs.edit().putString("test_url", url).apply();
	}

	@Nullable
	private ProxyConfig read(String prefix) {
		String type = prefs.getString(prefix + "type", null);
		if (type == null) return null;
		try {
			ProxyConfig c = new ProxyConfig(ProxyConfig.Type.valueOf(type),
					prefs.getString(prefix + "host", ""),
					prefs.getInt(prefix + "port", 0),
					prefs.getString(prefix + "user", ""),
					encrypted ? prefs.getString(prefix + "pass", "") : "",
					prefs.getString(prefix + "bypass", ""));
			return c.validate() == null ? c : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private void write(String prefix, @Nullable ProxyConfig c) {
		SharedPreferences.Editor e = prefs.edit();
		if (c == null) {
			e.remove(prefix + "type").remove(prefix + "host").remove(prefix + "port")
					.remove(prefix + "user").remove(prefix + "pass").remove(prefix + "bypass");
		} else {
			e.putString(prefix + "type", c.type.name())
					.putString(prefix + "host", c.host)
					.putInt(prefix + "port", c.port)
					.putString(prefix + "user", c.user)
					.putString(prefix + "bypass", c.bypass);
			if (encrypted) e.putString(prefix + "pass", c.password);
			else e.remove(prefix + "pass");
		}
		e.apply();
	}
}
