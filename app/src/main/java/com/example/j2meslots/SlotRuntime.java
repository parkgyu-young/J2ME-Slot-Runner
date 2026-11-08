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

package com.example.j2meslots;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import com.example.j2meslots.proxy.ProxyConfig;
import com.example.j2meslots.proxy.ProxyHolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/**
 * Per-process state of a slot. Lives in the ":slotN" process only; in any other process
 * (launcher, upstream ":midlet") {@link #active()} is false and every accessor returns the
 * neutral value, so the emulator core behaves exactly like upstream J2ME-Loader.
 * <p>
 * All data arrives through the launch Intent extras (no cross-process SharedPreferences reads).
 */
public final class SlotRuntime {
	private static final String TAG = "SlotRuntime";

	private static volatile int slot;
	private static volatile boolean keepAlive;
	private static volatile boolean legacyCerts;
	private static volatile String midletName;
	@SuppressWarnings({"FieldCanBeLocal", "unused"}) // must stay referenced while the process lives
	private static FileChannel lockChannel;
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
	private static volatile FileLock lock;

	private SlotRuntime() {
	}

	/**
	 * Must be called by the slot Activity before {@code MicroActivity.onCreate()}
	 * (that is before the MIDlet class loader computes its data directory).
	 */
	public static synchronized void init(Context context, int n, Intent intent) {
		slot = n;
		Bundle extras = intent != null ? intent.getExtras() : null;
		if (extras != null) {
			keepAlive = extras.getBoolean(Slots.EXTRA_KEEP_ALIVE, false);
			legacyCerts = extras.getBoolean(Slots.EXTRA_LEGACY_CERTS, false);
			midletName = extras.getString(Slots.EXTRA_MIDLET_NAME);
			Bundle proxy = extras.getBundle(Slots.EXTRA_PROXY);
			ProxyHolder.set(proxy != null ? ProxyConfig.fromBundle(proxy) : null);
		} else {
			ProxyHolder.set(null);
		}
		acquireLock(context, n);
		Log.i(TAG, "slot #" + n + " pid=" + Process.myPid() + " keepAlive=" + keepAlive
				+ " proxy=" + ProxyHolder.describe()); // never logs the password
	}

	/** True only inside a ":slotN" process. */
	public static boolean active() {
		return slot > 0;
	}

	public static int slot() {
		return slot;
	}

	/** Suffix appended to per-game data (RMS, cache) so every slot keeps its own save. */
	public static String dataSuffix() {
		return slot > 0 ? "_slot" + slot : "";
	}

	/** Do not pause the MIDlet / send hideNotify when the screen goes off or the app goes to background. */
	public static boolean keepRunning() {
		return slot > 0 && keepAlive;
	}

	public static boolean keepAlive() {
		return keepAlive;
	}

	/** Per-slot opt-in: accept expired / self-signed / untrusted TLS certificates. */
	public static boolean allowLegacyCerts() {
		return slot > 0 && legacyCerts;
	}

	public static String midletName() {
		return midletName;
	}

	/**
	 * Holds an exclusive lock on files/slots/slotN.lock for the whole life of the process.
	 * The kernel releases it automatically when the process dies, which makes it a reliable
	 * "is this slot running" probe for the launcher ({@link Slots#isRunning}).
	 */
	private static void acquireLock(Context context, int n) {
		if (lock != null) {
			return;
		}
		try {
			File dir = Slots.slotsDir(context);
			File file = new File(dir, "slot" + n + ".lock");
			RandomAccessFile raf = new RandomAccessFile(file, "rw");
			lockChannel = raf.getChannel();
			lock = lockChannel.tryLock();
			if (lock == null) {
				// The previous process of this slot is still dying: wait for its lock in background
				final FileChannel ch = lockChannel;
				Thread t = new Thread(() -> {
					try {
						lock = ch.lock();
					} catch (IOException | RuntimeException e) {
						Log.w(TAG, "slot lock wait failed", e);
					}
				}, "SlotLockWait");
				t.setDaemon(true);
				t.start();
			}
			try (FileOutputStream out = new FileOutputStream(new File(dir, "slot" + n + ".pid"))) {
				out.write(String.valueOf(Process.myPid()).getBytes(StandardCharsets.US_ASCII));
			}
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "acquireLock failed", e);
		}
	}
}
