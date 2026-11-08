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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.PreferenceManager;

import com.example.j2meslots.proxy.ProxyConfig;
import com.example.j2meslots.proxy.ProxyStore;
import com.example.j2meslots.slot.SlotClasses;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.FileUtils;

/**
 * Static helper used by the launcher process to manage slots.
 * <p>
 * Stored in SharedPreferences {@code slots_prefs}: {@code count} (1..20), {@code ids} (sorted list
 * of visible slot numbers), {@code dark}, {@code keep_alive}, {@code path_N} (app dir relative to
 * {@link Config#getAppDir()}), {@code name_N} (custom tile name), {@code midlet_N} (MIDlet title),
 * {@code legacy_certs_N}.
 * <p>
 * Slot processes never read these prefs: everything is passed through Intent extras.
 */
public final class Slots {
	private static final String TAG = "Slots";

	public static final int MAX = SlotClasses.COUNT;
	public static final int WARN_RUNNING = 4;
	public static final String PREFS = "slots_prefs";

	public static final String KEY_COUNT = "count";
	public static final String KEY_IDS = "ids";
	public static final String KEY_DARK = "dark";
	public static final String KEY_KEEP_ALIVE = "keep_alive";
	public static final String KEY_BG_PERMS_ASKED = "bg_perms_asked";

	/** Intent extras understood by {@link SlotRuntime}. */
	public static final String EXTRA_SLOT = "hsoSlot";
	public static final String EXTRA_MIDLET_NAME = Constants.KEY_MIDLET_NAME;
	public static final String EXTRA_KEEP_ALIVE = "hsoKeepAlive";
	public static final String EXTRA_LEGACY_CERTS = "hsoLegacyCerts";
	public static final String EXTRA_PROXY = "hsoProxy";

	private Slots() {
	}

	public static SharedPreferences prefs(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	// ---------------------------------------------------------------- components

	public static Class<? extends Activity> activity(int n) {
		checkSlot(n);
		return SlotClasses.activity(n);
	}

	public static Class<? extends Service> keepService(int n) {
		checkSlot(n);
		return SlotClasses.service(n);
	}

	public static String stopAction(Context ctx, int n) {
		return ctx.getPackageName() + ".slots.STOP_" + n;
	}

	public static String processName(Context ctx, int n) {
		return ctx.getPackageName() + ":slot" + n;
	}

	private static void checkSlot(int n) {
		if (n < 1 || n > MAX) throw new IllegalArgumentException("slot " + n);
	}

	// ---------------------------------------------------------------- tiles

	/** Sorted list of the visible slot numbers. */
	public static List<Integer> ids(Context ctx) {
		SharedPreferences sp = prefs(ctx);
		String raw = sp.getString(KEY_IDS, null);
		List<Integer> ids = new ArrayList<>();
		if (raw == null) {
			int count = Math.max(1, Math.min(MAX, sp.getInt(KEY_COUNT, 4)));
			for (int i = 1; i <= count; i++) ids.add(i);
			return ids;
		}
		for (String s : raw.split(",")) {
			try {
				int n = Integer.parseInt(s.trim());
				if (n >= 1 && n <= MAX && !ids.contains(n)) ids.add(n);
			} catch (NumberFormatException ignored) {
			}
		}
		if (ids.isEmpty()) ids.add(1);
		java.util.Collections.sort(ids);
		return ids;
	}

	private static void saveIds(Context ctx, List<Integer> ids) {
		StringBuilder sb = new StringBuilder();
		for (int n : ids) {
			if (sb.length() > 0) sb.append(',');
			sb.append(n);
		}
		prefs(ctx).edit().putString(KEY_IDS, sb.toString()).putInt(KEY_COUNT, ids.size()).apply();
	}

	public static int count(Context ctx) {
		return ids(ctx).size();
	}

	/** Grows (smallest free numbers first) or shrinks (from the end) the visible tiles. */
	public static void setCount(Context ctx, int count) {
		count = Math.max(1, Math.min(MAX, count));
		List<Integer> ids = ids(ctx);
		while (ids.size() > count) ids.remove(ids.size() - 1);
		for (int n = 1; ids.size() < count && n <= MAX; n++) {
			if (!ids.contains(n)) ids.add(n);
		}
		java.util.Collections.sort(ids);
		saveIds(ctx, ids);
	}

	/** Adds a tile; returns its slot number or -1 when 20 tiles already exist. */
	public static int addSlot(Context ctx) {
		List<Integer> ids = ids(ctx);
		for (int n = 1; n <= MAX; n++) {
			if (!ids.contains(n)) {
				ids.add(n);
				java.util.Collections.sort(ids);
				saveIds(ctx, ids);
				return n;
			}
		}
		return -1;
	}

	/** Removes the tile and forgets its game, name and preview (save data stays on disk). */
	public static void removeSlot(Context ctx, int n) {
		List<Integer> ids = ids(ctx);
		if (ids.size() <= 1) {
			clear(ctx, n);
			setName(ctx, n, null);
			return;
		}
		ids.remove(Integer.valueOf(n));
		saveIds(ctx, ids);
		clear(ctx, n);
		setName(ctx, n, null);
		prefs(ctx).edit().remove("legacy_certs_" + n).apply();
		ProxyStore.get(ctx).removeSlot(n);
	}

	public static void assign(Context ctx, int n, String relativePath, String midletTitle) {
		checkSlot(n);
		prefs(ctx).edit()
				.putString("path_" + n, relativePath)
				.putString("midlet_" + n, midletTitle)
				.apply();
		//noinspection ResultOfMethodCallIgnored
		previewFile(ctx, n).delete();
	}

	public static void clear(Context ctx, int n) {
		prefs(ctx).edit().remove("path_" + n).remove("midlet_" + n).apply();
		//noinspection ResultOfMethodCallIgnored
		previewFile(ctx, n).delete();
	}

	/** Custom tile name or null. */
	@Nullable
	public static String name(Context ctx, int n) {
		String s = prefs(ctx).getString("name_" + n, null);
		return s == null || s.trim().isEmpty() ? null : s;
	}

	public static void setName(Context ctx, int n, @Nullable String name) {
		SharedPreferences.Editor e = prefs(ctx).edit();
		if (name == null || name.trim().isEmpty()) e.remove("name_" + n);
		else e.putString("name_" + n, name.trim());
		e.apply();
	}

	/** Relative app path (see {@link #absolutePath}) or null when no game is assigned. */
	@Nullable
	public static String path(Context ctx, int n) {
		return prefs(ctx).getString("path_" + n, null);
	}

	@Nullable
	public static String absolutePath(Context ctx, int n) {
		String p = path(ctx, n);
		return p == null ? null : Config.getAppDir() + p;
	}

	@Nullable
	public static String midletTitle(Context ctx, int n) {
		return prefs(ctx).getString("midlet_" + n, null);
	}

	/** Custom name, else MIDlet title, else "Ô #N". */
	public static String title(Context ctx, int n) {
		String s = name(ctx, n);
		if (s == null) s = midletTitle(ctx, n);
		return s != null ? s : ctx.getString(R.string.slot_default_name, n);
	}

	public static boolean keepAlive(Context ctx) {
		return prefs(ctx).getBoolean(KEY_KEEP_ALIVE, false);
	}

	public static boolean dark(Context ctx) {
		return prefs(ctx).getBoolean(KEY_DARK, false);
	}

	public static boolean legacyCerts(Context ctx, int n) {
		return prefs(ctx).getBoolean("legacy_certs_" + n, false);
	}

	public static void setLegacyCerts(Context ctx, int n, boolean allow) {
		prefs(ctx).edit().putBoolean("legacy_certs_" + n, allow).apply();
	}

	// ---------------------------------------------------------------- files

	public static File slotsDir(Context ctx) {
		File dir = new File(ctx.getFilesDir(), "slots");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		return dir;
	}

	public static File previewFile(Context ctx, int n) {
		return new File(slotsDir(ctx), "slot" + n + ".jpg");
	}

	private static File lockFile(Context ctx, int n) {
		return new File(slotsDir(ctx), "slot" + n + ".lock");
	}

	/**
	 * Heartbeat probe: the slot process holds an exclusive lock on files/slots/slotN.lock for
	 * its whole life; the kernel drops it when the process dies (also when Android kills it).
	 */
	public static boolean isRunning(Context ctx, int n) {
		File f = lockFile(ctx, n);
		if (!f.exists()) return false;
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw"); FileChannel ch = raf.getChannel()) {
			FileLock l = ch.tryLock();
			if (l == null) return true;
			l.release();
			return false;
		} catch (OverlappingFileLockException e) {
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public static int runningCount(Context ctx) {
		int c = 0;
		for (int n = 1; n <= MAX; n++) {
			if (isRunning(ctx, n)) c++;
		}
		return c;
	}

	// ---------------------------------------------------------------- library hooks

	/** Called by the library when a game is deleted: forget it in slots and drop per-slot saves. */
	public static void onAppDeleted(Context ctx, String relativePath) {
		if (relativePath == null) return;
		for (int n = 1; n <= MAX; n++) {
			if (relativePath.equals(path(ctx, n))) clear(ctx, n);
		}
		for (int n = 1; n <= MAX; n++) {
			File dir = new File(Config.getDataDir(), relativePath + "_slot" + n);
			if (dir.exists()) FileUtils.deleteDirectory(dir);
		}
	}

	/** Called by the installer when an update changes the app folder name: keep slot saves. */
	public static void onAppPathChanged(Context ctx, String oldPath, String newPath) {
		if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;
		SharedPreferences.Editor e = prefs(ctx).edit();
		for (int n = 1; n <= MAX; n++) {
			if (oldPath.equals(path(ctx, n))) e.putString("path_" + n, newPath);
			File from = new File(Config.getDataDir(), oldPath + "_slot" + n);
			if (from.exists()) {
				File to = new File(Config.getDataDir(), newPath + "_slot" + n);
				FileUtils.deleteDirectory(to);
				//noinspection ResultOfMethodCallIgnored
				from.renameTo(to);
			}
		}
		e.apply();
	}

	// ---------------------------------------------------------------- start / stop

	public static class SlotException extends Exception {
		@StringRes
		public final int messageRes;

		public SlotException(@StringRes int messageRes) {
			super("slot error " + messageRes);
			this.messageRes = messageRes;
		}
	}

	/**
	 * Launches (or brings to front) the slot. Everything the slot needs is packed in the Intent.
	 */
	public static void start(@NonNull Context ctx, int n) throws SlotException {
		checkSlot(n);
		String path = absolutePath(ctx, n);
		if (path == null) throw new SlotException(R.string.slot_err_no_game);
		File appDir = new File(path);
		if (!new File(appDir, Config.MIDLET_DEX_FILE).isFile()) {
			throw new SlotException(R.string.slot_err_game_missing);
		}
		boolean running = isRunning(ctx, n);
		if (!running) {
			if (!ensureConfig(ctx, appDir)) throw new SlotException(R.string.slot_err_config);
			removeStaleTasks(ctx, n);
		}
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(path), ctx, activity(n));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		intent.putExtra(EXTRA_SLOT, n);
		intent.putExtra(EXTRA_MIDLET_NAME, midletTitle(ctx, n));
		intent.putExtra(EXTRA_KEEP_ALIVE, keepAlive(ctx));
		intent.putExtra(EXTRA_LEGACY_CERTS, legacyCerts(ctx, n));
		ProxyConfig proxy = ProxyStore.get(ctx).effectiveFor(n);
		if (proxy != null) intent.putExtra(EXTRA_PROXY, proxy.toBundle());
		ctx.startActivity(intent);
	}

	/** Asks the slot process to finish its MIDlet and exit (internal broadcast). */
	public static void stop(@NonNull Context ctx, int n) {
		Intent b = new Intent(stopAction(ctx, n));
		b.setPackage(ctx.getPackageName());
		ctx.sendBroadcast(b);
	}

	/** Fallback when the slot does not react to {@link #stop}: kill its process and task. */
	public static void forceStop(@NonNull Context ctx, int n) {
		ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
		if (am != null) {
			List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
			if (list != null) {
				String name = processName(ctx, n);
				for (ActivityManager.RunningAppProcessInfo info : list) {
					if (name.equals(info.processName) && info.pid != Process.myPid()) {
						Process.killProcess(info.pid);
					}
				}
			}
		}
		removeStaleTasks(ctx, n);
	}

	/** Removes recents/task records left by a dead slot so a fresh launch gets the new Intent. */
	public static void removeStaleTasks(Context ctx, int n) {
		try {
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return;
			ComponentName target = new ComponentName(ctx, activity(n));
			for (ActivityManager.AppTask task : am.getAppTasks()) {
				ComponentName base;
				try {
					base = task.getTaskInfo().baseIntent.getComponent();
				} catch (IllegalArgumentException e) {
					continue; // task already gone
				}
				if (target.equals(base)) {
					task.finishAndRemoveTask();
				}
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "removeStaleTasks", e);
		}
	}

	/**
	 * Makes sure the game has a config in "configs/" (created from the default profile, or from
	 * built-in defaults) so the slot never falls back to the interactive ConfigActivity.
	 */
	public static boolean ensureConfig(Context ctx, File appDir) {
		File parent = appDir.getParentFile();
		if (parent == null || parent.getParent() == null) return false;
		File configDir = new File(parent.getParent() + Config.MIDLET_CONFIGS_DIR + appDir.getName());
		if (ProfilesManager.loadConfig(configDir) != null) return true;
		if (!configDir.isDirectory() && !configDir.mkdirs()) return false;
		String defProfile = PreferenceManager.getDefaultSharedPreferences(ctx)
				.getString(Constants.PREF_DEFAULT_PROFILE, null);
		if (defProfile != null) {
			File src = new File(Config.getProfilesDir(), defProfile);
			if (src.isDirectory()) {
				FileUtils.copyFiles(src, configDir, null);
				if (ProfilesManager.loadConfig(configDir) != null) return true;
			}
		}
		ProfileModel params = new ProfileModel(configDir);
		return ProfilesManager.saveConfig(params);
	}
}
