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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.example.j2meslots.Slots;
import com.example.j2meslots.ui.GridActivity;

import ru.playsoftware.j2meloader.R;

/**
 * Foreground service (type specialUse) that keeps a slot process alive while its game runs in
 * the background or with the screen off. Holds a partial wake lock and a Wi-Fi lock.
 * Runs in the same ":slotN" process as {@code SlotActivityN}.
 */
public abstract class SlotKeepService extends Service {
	private static final String TAG = "SlotKeepService";
	public static final String CHANNEL_ID = "slots_keep_alive";

	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;

	/** 1..20, implemented by the generated subclasses. */
	protected abstract int slotNumber();

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// Android 14: must reach startForeground() within a few seconds of startForegroundService()
		String game = intent != null ? intent.getStringExtra(Slots.EXTRA_MIDLET_NAME) : null;
		try {
			ServiceCompat.startForeground(this, 1000 + slotNumber(), buildNotification(game),
					Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
		} catch (RuntimeException e) {
			Log.w(TAG, "startForeground failed", e);
			stopSelf();
			return START_NOT_STICKY;
		}
		acquireLocks();
		return START_NOT_STICKY;
	}

	@SuppressLint("WakelockTimeout") // released in onDestroy / when the slot stops
	private void acquireLocks() {
		if (wakeLock == null) {
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			if (pm != null) {
				wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
						"J2MESlotRunner:slot" + slotNumber());
				wakeLock.setReferenceCounted(false);
				wakeLock.acquire();
			}
		}
		if (wifiLock == null) {
			try {
				WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
				if (wm != null) {
					//noinspection deprecation
					wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
							"J2MESlotRunner:slot" + slotNumber());
					wifiLock.setReferenceCounted(false);
					wifiLock.acquire();
				}
			} catch (RuntimeException e) {
				Log.w(TAG, "wifi lock", e);
			}
		}
	}

	private void releaseLocks() {
		if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
		wakeLock = null;
		if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
		wifiLock = null;
	}

	private Notification buildNotification(@Nullable String game) {
		int n = slotNumber();
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
			NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
					getString(R.string.slot_channel_name), NotificationManager.IMPORTANCE_LOW);
			ch.setShowBadge(false);
			nm.createNotificationChannel(ch);
		}
		int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		Intent open = new Intent(this, Slots.activity(n)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent openPi = PendingIntent.getActivity(this, n, open, piFlags);
		Intent stop = new Intent(Slots.stopAction(this, n)).setPackage(getPackageName());
		PendingIntent stopPi = PendingIntent.getBroadcast(this, 100 + n, stop, piFlags);
		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_slot_notification)
				.setContentTitle(getString(R.string.slot_notification_title, n))
				.setContentText(game != null ? game : getString(R.string.slot_notification_text))
				.setContentIntent(openPi)
				.addAction(0, getString(R.string.slot_action_stop), stopPi)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
				.build();
	}

	/**
	 * The user swiped this slot's task, or the launcher task, out of Recents: stop the service,
	 * delete the tile preview and kill this slot's process.
	 */
	@Override
	public void onTaskRemoved(Intent rootIntent) {
		ComponentName c = rootIntent != null ? rootIntent.getComponent() : null;
		String cls = c != null ? c.getClassName() : null;
		boolean mine = Slots.activity(slotNumber()).getName().equals(cls)
				|| GridActivity.class.getName().equals(cls);
		if (!mine) {
			return; // another slot's task
		}
		Log.i(TAG, "task removed, stopping slot #" + slotNumber());
		releaseLocks();
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
		stopSelf();
		//noinspection ResultOfMethodCallIgnored
		Slots.previewFile(this, slotNumber()).delete();
		Process.killProcess(Process.myPid());
	}

	@Override
	public void onDestroy() {
		releaseLocks();
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
