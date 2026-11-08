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

package com.example.j2meslots.slot;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.j2meslots.SlotRuntime;
import com.example.j2meslots.Slots;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.shell.MicroActivity;

import ru.playsoftware.j2meloader.R;

/**
 * Common code of the 20 generated {@code SlotActivityN} classes. Each one runs the unchanged
 * J2ME-Loader {@link MicroActivity} in its own ":slotN" process.
 */
public abstract class SlotActivityBase extends MicroActivity {
	private static final String TAG = "SlotActivity";
	private static final long PREVIEW_INTERVAL_MS = 3000;
	private static final int PREVIEW_MAX_SIDE = 360;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
	private BroadcastReceiver stopReceiver;
	private boolean stopping;

	/** 1..20, implemented by the generated subclasses. */
	protected abstract int slotNumber();

	private final Runnable previewTask = new Runnable() {
		@Override
		public void run() {
			savePreviewAsync();
			handler.postDelayed(this, PREVIEW_INTERVAL_MS);
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		final int n = slotNumber();
		// Before MicroActivity.onCreate: the class loader reads SlotRuntime.dataSuffix()
		SlotRuntime.init(getApplicationContext(), n, getIntent());
		super.onCreate(savedInstanceState);
		registerStopReceiver(n);
		updateTaskDescription(n);
		if (SlotRuntime.keepAlive()) {
			startKeepService(n);
		}
		handler.postDelayed(previewTask, PREVIEW_INTERVAL_MS);
	}

	@Override
	public void onResume() {
		super.onResume();
		// MicroActivity.onResume() skipped resumeApp() if we suppressed the pause; reset the flag
		if (SlotRuntime.keepRunning()) {
			setPauseSuppressed(false);
		}
	}

	@Override
	public void onPause() {
		if (SlotRuntime.keepRunning()) {
			// Background mode: do not call MIDlet.pauseApp() when leaving the screen
			setPauseSuppressed(true);
		}
		super.onPause();
		savePreviewAsync();
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		if (stopReceiver != null) {
			try {
				unregisterReceiver(stopReceiver);
			} catch (IllegalArgumentException ignored) {
			}
			stopReceiver = null;
		}
		stopKeepService(slotNumber());
		previewExecutor.shutdown();
		// MicroActivity.onDestroy() destroys the MIDlet and kills this process within ~1 s
		super.onDestroy();
	}

	private void registerStopReceiver(int n) {
		stopReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				stopSlot();
			}
		};
		ContextCompat.registerReceiver(this, stopReceiver,
				new IntentFilter(Slots.stopAction(this, n)), ContextCompat.RECEIVER_NOT_EXPORTED);
	}

	/** Internal STOP broadcast: finish the MIDlet and exit this slot process only. */
	void stopSlot() {
		if (stopping) return;
		stopping = true;
		Log.i(TAG, "stop requested for slot #" + slotNumber());
		stopKeepService(slotNumber());
		finish();
		// Safety net if the MIDlet blocks destroyApp()
		new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 3000);
	}

	private void startKeepService(int n) {
		Intent i = new Intent(this, Slots.keepService(n));
		i.putExtra(Slots.EXTRA_MIDLET_NAME, SlotRuntime.midletName());
		try {
			ContextCompat.startForegroundService(this, i);
		} catch (RuntimeException e) {
			// e.g. ForegroundServiceStartNotAllowedException: the game still runs, only in foreground
			Log.w(TAG, "cannot start keep-alive service", e);
		}
	}

	private void stopKeepService(int n) {
		try {
			stopService(new Intent(this, Slots.keepService(n)));
		} catch (RuntimeException ignored) {
		}
	}

	private void updateTaskDescription(int n) {
		String name = SlotRuntime.midletName();
		String label = getString(R.string.slot_task_label, n, name != null ? name : "");
		setTitle(label);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			//noinspection deprecation
			setTaskDescription(new ActivityManager.TaskDescription(label));
		}
	}

	/** Saves the last frame to files/slots/slotN.jpg for the launcher tile. */
	private void savePreviewAsync() {
		final Displayable current = getCurrent();
		if (!(current instanceof Canvas) || previewExecutor.isShutdown()) {
			return;
		}
		final File out = Slots.previewFile(getApplicationContext(), slotNumber());
		try {
			previewExecutor.execute(() -> writePreview((Canvas) current, out));
		} catch (RuntimeException ignored) {
		}
	}

	private static void writePreview(Canvas canvas, File out) {
		Bitmap frame = null;
		Bitmap scaled = null;
		try {
			frame = canvas.copyLastFrame();
			if (frame == null) return;
			int w = frame.getWidth();
			int h = frame.getHeight();
			float k = Math.min(1f, (float) PREVIEW_MAX_SIDE / Math.max(w, h));
			scaled = k < 1f ? Bitmap.createScaledBitmap(frame, Math.max(1, (int) (w * k)),
					Math.max(1, (int) (h * k)), true) : frame;
			File tmp = new File(out.getPath() + ".tmp");
			try (FileOutputStream fos = new FileOutputStream(tmp)) {
				scaled.compress(Bitmap.CompressFormat.JPEG, 80, fos);
			}
			if (!tmp.renameTo(out)) {
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
			}
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			Log.w(TAG, "preview failed: " + e);
		} finally {
			if (scaled != null && scaled != frame) scaled.recycle();
			if (frame != null) frame.recycle();
		}
	}
}
