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

package com.example.j2meslots.ui;

import static ru.playsoftware.j2meloader.util.Constants.PREF_LAST_PATH;
import static ru.playsoftware.j2meloader.util.Constants.PREF_TOOLBAR;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.example.j2meslots.Slots;
import com.example.j2meslots.proxy.ProxyStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import ru.playsoftware.j2meloader.MainActivity;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.info.AboutDialogFragment;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.installer.BatchInstallerDialog;
import ru.woesss.j2me.installer.InstallerDialog;

/**
 * Launcher: grid of up to 20 slots. Each tile shows the last frame of its game, the title,
 * a live status (from the slot process heartbeat lock) and ▶ ■ ☰ buttons.
 */
public class GridActivity extends AppCompatActivity {
	private static final long TICK_MS = 1000;
	private static final long FORCE_STOP_DELAY_MS = 3500;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService io = Executors.newSingleThreadExecutor();
	private final SparseArray<SlotState> states = new SparseArray<>();
	private SlotAdapter adapter;
	private GridLayoutManager layoutManager;
	private AppListModel appListModel;
	private List<AppItem> apps = Collections.emptyList();
	private boolean ticking;
	private volatile boolean refreshing;
	private int pendingInstallSlot = -1;
	private final Set<String> pathsBeforeInstall = new HashSet<>();

	private final ActivityResultLauncher<String> pickFiles =
			registerForActivityResult(FileUtils.getFilePicker(), this::onFilesPicked);
	private final ActivityResultLauncher<String> storagePermission =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onStoragePermission);
	private final ActivityResultLauncher<String> notificationPermission =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> askBatteryOptimization());

	/** Live state of a tile, computed off the main thread. */
	static final class SlotState {
		boolean running;
		boolean proxy;
		long previewStamp;
		Bitmap preview;
	}

	private final Runnable tick = new Runnable() {
		@Override
		public void run() {
			if (!ticking) return;
			refreshStates();
			handler.postDelayed(this, TICK_MS);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		getDelegate().setLocalNightMode(Slots.dark(this)
				? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_grid);
		Toolbar toolbar = findViewById(R.id.grid_toolbar);
		setSupportActionBar(toolbar);
		setTitle(R.string.app_name);

		RecyclerView recycler = findViewById(R.id.grid_recycler);
		adapter = new SlotAdapter();
		layoutManager = new GridLayoutManager(this, spanCount(getResources().getConfiguration()));
		layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				return adapter.getItemViewType(position) == SlotAdapter.TYPE_ADD ? layoutManager.getSpanCount() : 1;
			}
		});
		recycler.setLayoutManager(layoutManager);
		recycler.setAdapter(adapter);
		RecyclerView.ItemAnimator animator = recycler.getItemAnimator();
		if (animator instanceof SimpleItemAnimator) {
			((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
		}

		appListModel = new ViewModelProvider(this).get(AppListModel.class);
		appListModel.getAppRepository().observeApps(this, this::onAppsChanged);
		appListModel.getAppRepository().observeErrors(this, e ->
				Toast.makeText(this, getString(R.string.error) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show());

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (!preferences.contains(PREF_TOOLBAR)) {
			boolean enable = !ViewConfiguration.get(this).hasPermanentMenuKey();
			preferences.edit().putBoolean(PREF_TOOLBAR, enable).apply();
		}
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		checkStorage();
		adapter.reload();
	}

	@Override
	protected void onResume() {
		super.onResume();
		refreshProxyFlags();
		adapter.reload();
		ticking = true;
		handler.removeCallbacks(tick);
		handler.post(tick);
	}

	@Override
	protected void onPause() {
		ticking = false;
		handler.removeCallbacks(tick);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		io.shutdownNow();
		super.onDestroy();
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		layoutManager.setSpanCount(spanCount(newConfig));
	}

	/** 2 columns on phones (3 in landscape), 3–4 on tablets. */
	private static int spanCount(Configuration c) {
		boolean landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
		if (c.smallestScreenWidthDp < 600) return landscape ? 3 : 2;
		return c.screenWidthDp >= 900 ? 4 : 3;
	}

	// ------------------------------------------------------------------ storage

	private void checkStorage() {
		if (FileUtils.isExternalStorageLegacy() && ContextCompat.checkSelfPermission(this,
				Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
			return;
		}
		onStorageReady();
	}

	private void onStoragePermission(boolean granted) {
		if (granted) {
			onStorageReady();
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.error)
				.setMessage(R.string.storage_permission_needed)
				.setCancelable(false)
				.setPositiveButton(R.string.retry, (d, w) ->
						storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE))
				.setNegativeButton(R.string.exit, (d, w) -> finish())
				.show();
	}

	private void onStorageReady() {
		File dir = new File(Config.getEmulatorDir());
		if (FileUtils.initWorkDir(dir)) {
			appListModel.onWorkDirReady();
		} else {
			// The library screen knows how to pick/create another emulator folder
			startActivity(new Intent(this, MainActivity.class));
		}
	}

	// ------------------------------------------------------------------ menu

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.grid_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.grid_action_library || id == R.id.grid_action_library_text) {
			startActivity(new Intent(this, MainActivity.class));
		} else if (id == R.id.grid_action_settings) {
			showSettingsDialog();
		} else if (id == R.id.grid_action_proxy) {
			startActivity(new Intent(this, ProxySettingsActivity.class));
		} else if (id == R.id.grid_action_emulator_settings) {
			startActivity(new Intent(this, SettingsActivity.class));
		} else if (id == R.id.grid_action_about) {
			new AboutDialogFragment().show(getSupportFragmentManager(), "about");
		} else {
			return super.onOptionsItemSelected(item);
		}
		return true;
	}

	private void showSettingsDialog() {
		final boolean[] checked = {Slots.dark(this), Slots.keepAlive(this)};
		CharSequence[] items = {getString(R.string.settings_dark), getString(R.string.settings_keep_alive)};
		new AlertDialog.Builder(this)
				.setTitle(R.string.settings_title)
				.setMultiChoiceItems(items, checked.clone(), (d, which, isChecked) -> checked[which] = isChecked)
				.setPositiveButton(R.string.action_save, (d, w) -> applySettings(checked[0], checked[1]))
				.setNegativeButton(R.string.action_cancel, null)
				.setNeutralButton(R.string.menu_proxy, (d, w) ->
						startActivity(new Intent(this, ProxySettingsActivity.class)))
				.show();
	}

	private void applySettings(boolean dark, boolean keepAlive) {
		boolean darkChanged = dark != Slots.dark(this);
		boolean keepChanged = keepAlive != Slots.keepAlive(this);
		Slots.prefs(this).edit()
				.putBoolean(Slots.KEY_DARK, dark)
				.putBoolean(Slots.KEY_KEEP_ALIVE, keepAlive)
				.apply();
		if (keepChanged) {
			Toast.makeText(this, R.string.keep_alive_applies, Toast.LENGTH_LONG).show();
		}
		if (keepChanged && keepAlive) {
			Slots.prefs(this).edit().putBoolean(Slots.KEY_BG_PERMS_ASKED, true).apply();
			requestBackgroundPermissions();
		} else if (darkChanged) {
			recreate();
		}
	}

	/** First enable of background mode: notifications (Android 13+) then battery optimization. */
	private void requestBackgroundPermissions() {
		if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
				Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
		} else {
			askBatteryOptimization();
		}
	}

	private void askBatteryOptimization() {
		boolean darkChanged = (getDelegate().getLocalNightMode() == AppCompatDelegate.MODE_NIGHT_YES) != Slots.dark(this);
		if (Build.VERSION.SDK_INT < 23 || isIgnoringBatteryOptimizations()) {
			if (darkChanged) recreate();
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.battery_title)
				.setMessage(R.string.battery_message)
				.setPositiveButton(R.string.battery_open, (d, w) -> openBatterySettings())
				.setNegativeButton(R.string.action_close, null)
				.setOnDismissListener(d -> {
					if (darkChanged) recreate();
				})
				.show();
	}

	private boolean isIgnoringBatteryOptimizations() {
		if (Build.VERSION.SDK_INT < 23) return true;
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
	}

	@SuppressLint("BatteryLife") // user explicitly enabled background running
	private void openBatterySettings() {
		if (Build.VERSION.SDK_INT < 23) return;
		try {
			startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
					Uri.parse("package:" + getPackageName())));
		} catch (ActivityNotFoundException | SecurityException e) {
			try {
				startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
			} catch (ActivityNotFoundException e2) {
				Toast.makeText(this, R.string.battery_message, Toast.LENGTH_LONG).show();
			}
		}
	}

	// ------------------------------------------------------------------ live state

	private void refreshProxyFlags() {
		final Context ctx = getApplicationContext();
		final List<Integer> ids = Slots.ids(ctx);
		try {
			io.execute(() -> {
				ProxyStore store = ProxyStore.get(ctx);
				final boolean[] flags = new boolean[ids.size()];
				for (int i = 0; i < ids.size(); i++) flags[i] = store.effectiveFor(ids.get(i)) != null;
				handler.post(() -> {
					if (isDestroyed()) return;
					for (int i = 0; i < ids.size(); i++) {
						SlotState s = state(ids.get(i));
						if (s.proxy != flags[i]) {
							s.proxy = flags[i];
							adapter.notifySlotChanged(ids.get(i));
						}
					}
				});
			});
		} catch (RejectedExecutionException ignored) {
		}
	}

	private SlotState state(int n) {
		SlotState s = states.get(n);
		if (s == null) {
			s = new SlotState();
			states.put(n, s);
		}
		return s;
	}

	/** Heartbeat + preview probe on the IO thread, then a minimal UI update. */
	private void refreshStates() {
		if (refreshing) return; // previous probe still running
		refreshing = true;
		final Context ctx = getApplicationContext();
		final List<Integer> ids = adapter.ids();
		final long[] stamps = new long[ids.size()];
		for (int i = 0; i < ids.size(); i++) stamps[i] = state(ids.get(i)).previewStamp;
		try {
			io.execute(() -> {
				final boolean[] running = new boolean[ids.size()];
				final long[] newStamps = new long[ids.size()];
				final Bitmap[] previews = new Bitmap[ids.size()];
				for (int i = 0; i < ids.size(); i++) {
					int n = ids.get(i);
					running[i] = Slots.isRunning(ctx, n);
					File f = Slots.previewFile(ctx, n);
					long stamp = f.isFile() ? f.lastModified() + f.length() : 0;
					newStamps[i] = stamp;
					if (stamp != 0 && stamp != stamps[i]) {
						BitmapFactory.Options o = new BitmapFactory.Options();
						o.inPreferredConfig = Bitmap.Config.RGB_565;
						previews[i] = BitmapFactory.decodeFile(f.getPath(), o);
						if (previews[i] == null) newStamps[i] = stamps[i];
					}
				}
				handler.post(() -> {
					refreshing = false;
					if (isDestroyed()) return;
					for (int i = 0; i < ids.size(); i++) {
						int n = ids.get(i);
						SlotState s = state(n);
						boolean changed = s.running != running[i];
						s.running = running[i];
						if (newStamps[i] == 0) {
							if (s.preview != null) changed = true;
							s.preview = null;
							s.previewStamp = 0;
						} else if (previews[i] != null) {
							s.preview = previews[i];
							s.previewStamp = newStamps[i];
							changed = true;
						}
						if (changed) adapter.notifySlotChanged(n);
					}
				});
			});
		} catch (RejectedExecutionException ignored) {
			refreshing = false;
		}
	}

	private int runningCount() {
		int c = 0;
		for (int n : adapter.ids()) {
			if (state(n).running) c++;
		}
		return c;
	}

	// ------------------------------------------------------------------ actions

	private void onStartClicked(int n) {
		if (Slots.path(this, n) == null) {
			showChooseGame(n);
			return;
		}
		if (!state(n).running && runningCount() >= Slots.WARN_RUNNING) {
			new AlertDialog.Builder(this)
					.setTitle(R.string.warn_many_title)
					.setMessage(getString(R.string.warn_many_message, runningCount()))
					.setPositiveButton(R.string.action_continue, (d, w) -> doStart(n))
					.setNegativeButton(R.string.action_cancel, null)
					.show();
			return;
		}
		doStart(n);
	}

	private void doStart(int n) {
		try {
			Slots.start(this, n);
		} catch (Slots.SlotException e) {
			new AlertDialog.Builder(this)
					.setTitle(Slots.title(this, n))
					.setMessage(e.messageRes)
					.setPositiveButton(R.string.menu_choose_game, (d, w) -> showChooseGame(n))
					.setNegativeButton(R.string.action_close, null)
					.show();
		} catch (RuntimeException e) {
			new AlertDialog.Builder(this)
					.setTitle(R.string.error)
					.setMessage(getString(R.string.slot_err_start, String.valueOf(e.getMessage())))
					.setPositiveButton(android.R.string.ok, null)
					.show();
		}
	}

	private void stopSlot(int n, Runnable after) {
		Slots.stop(this, n);
		Toast.makeText(this, getString(R.string.slot_stopping, n), Toast.LENGTH_SHORT).show();
		final Context ctx = getApplicationContext();
		handler.postDelayed(() -> {
			try {
				io.execute(() -> {
					if (Slots.isRunning(ctx, n)) Slots.forceStop(ctx, n);
					handler.post(() -> {
						if (after != null && !isDestroyed()) after.run();
					});
				});
			} catch (RejectedExecutionException ignored) {
			}
		}, FORCE_STOP_DELAY_MS);
	}

	private void showTileMenu(View anchor, int n) {
		PopupMenu popup = new PopupMenu(this, anchor);
		Menu m = popup.getMenu();
		m.add(0, 1, 0, R.string.menu_install_game);
		m.add(0, 2, 1, R.string.menu_choose_game);
		m.add(0, 3, 2, R.string.menu_clear_game).setEnabled(Slots.path(this, n) != null);
		m.add(0, 4, 3, R.string.menu_rename_slot);
		m.add(0, 5, 4, R.string.menu_delete_slot);
		popup.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case 1:
					installForSlot(n);
					return true;
				case 2:
					showChooseGame(n);
					return true;
				case 3:
					clearGame(n);
					return true;
				case 4:
					showRename(n);
					return true;
				case 5:
					confirmDelete(n);
					return true;
				default:
					return false;
			}
		});
		popup.show();
	}

	private void installForSlot(int n) {
		pendingInstallSlot = n;
		pathsBeforeInstall.clear();
		for (AppItem a : apps) pathsBeforeInstall.add(a.getPath());
		String path = PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_LAST_PATH, null);
		if (path == null) {
			File dir = Environment.getExternalStorageDirectory();
			if (dir.canRead()) path = dir.getAbsolutePath();
		}
		try {
			pickFiles.launch(path);
		} catch (ActivityNotFoundException e) {
			pendingInstallSlot = -1;
			Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_SHORT).show();
		}
	}

	private void onFilesPicked(List<Uri> uris) {
		if (uris == null || uris.isEmpty()) {
			pendingInstallSlot = -1;
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(this).edit()
				.putString(PREF_LAST_PATH, uris.get(0).getPath()).apply();
		if (uris.size() == 1) {
			InstallerDialog.newInstance(uris.get(0)).show(getSupportFragmentManager(), "installer");
		} else {
			BatchInstallerDialog.newInstance(uris).show(getSupportFragmentManager(), "installer");
		}
	}

	private void onAppsChanged(List<AppItem> list) {
		apps = list != null ? list : Collections.emptyList();
		if (pendingInstallSlot > 0) {
			for (AppItem a : apps) {
				if (!pathsBeforeInstall.contains(a.getPath())) {
					int n = pendingInstallSlot;
					pendingInstallSlot = -1;
					assignGame(n, a);
					break;
				}
			}
		}
		adapter.notifyDataSetChanged();
	}

	private void showChooseGame(int n) {
		pendingInstallSlot = -1;
		if (apps.isEmpty()) {
			new AlertDialog.Builder(this)
					.setTitle(getString(R.string.choose_title, n))
					.setMessage(R.string.choose_empty)
					.setPositiveButton(R.string.btn_install_game, (d, w) -> installForSlot(n))
					.setNegativeButton(R.string.action_close, null)
					.show();
			return;
		}
		final List<AppItem> list = new ArrayList<>(apps);
		CharSequence[] items = new CharSequence[list.size()];
		for (int i = 0; i < list.size(); i++) {
			AppItem a = list.get(i);
			String author = a.getAuthor();
			items[i] = author == null || author.isEmpty() ? a.getTitle() : a.getTitle() + " — " + author;
		}
		new AlertDialog.Builder(this)
				.setTitle(getString(R.string.choose_title, n))
				.setItems(items, (d, which) -> assignGame(n, list.get(which)))
				.setNeutralButton(R.string.btn_install_game, (d, w) -> installForSlot(n))
				.setNegativeButton(R.string.action_close, null)
				.show();
	}

	private void assignGame(int n, AppItem item) {
		if (state(n).running) {
			new AlertDialog.Builder(this)
					.setMessage(getString(R.string.slot_running_change, n))
					.setPositiveButton(R.string.slot_stop_and_continue, (d, w) ->
							stopSlot(n, () -> doAssign(n, item)))
					.setNegativeButton(R.string.action_cancel, null)
					.show();
			return;
		}
		doAssign(n, item);
	}

	private void doAssign(int n, AppItem item) {
		Slots.assign(this, n, item.getPath(), item.getTitle());
		state(n).preview = null;
		state(n).previewStamp = 0;
		Toast.makeText(this, getString(R.string.slot_assigned, item.getTitle(), n), Toast.LENGTH_SHORT).show();
		adapter.notifySlotChanged(n);
	}

	private void clearGame(int n) {
		Runnable clear = () -> {
			Slots.clear(this, n);
			state(n).preview = null;
			state(n).previewStamp = 0;
			adapter.notifySlotChanged(n);
		};
		if (state(n).running) {
			new AlertDialog.Builder(this)
					.setMessage(getString(R.string.slot_running_change, n))
					.setPositiveButton(R.string.slot_stop_and_continue, (d, w) -> stopSlot(n, clear))
					.setNegativeButton(R.string.action_cancel, null)
					.show();
		} else {
			clear.run();
		}
	}

	private void showRename(int n) {
		final EditText edit = new EditText(this);
		edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		edit.setSingleLine(true);
		String current = Slots.name(this, n);
		edit.setText(current != null ? current : "");
		edit.setSelection(edit.getText().length());
		edit.setHint(Slots.midletTitle(this, n) != null ? Slots.midletTitle(this, n)
				: getString(R.string.slot_default_name, n));
		FrameLayout box = new FrameLayout(this);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(edit);
		new AlertDialog.Builder(this)
				.setTitle(getString(R.string.rename_title, n))
				.setView(box)
				.setPositiveButton(R.string.action_save, (d, w) -> {
					Slots.setName(this, n, edit.getText().toString());
					adapter.notifySlotChanged(n);
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void confirmDelete(int n) {
		boolean running = state(n).running;
		new AlertDialog.Builder(this)
				.setMessage(getString(running ? R.string.slot_delete_running : R.string.slot_delete_confirm, n))
				.setPositiveButton(R.string.menu_delete_slot, (d, w) -> {
					Runnable remove = () -> {
						Slots.removeSlot(this, n);
						states.remove(n);
						adapter.reload();
					};
					if (running) stopSlot(n, remove);
					else remove.run();
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void addSlot() {
		int n = Slots.addSlot(this);
		if (n < 0) {
			Toast.makeText(this, getString(R.string.slot_max, Slots.MAX), Toast.LENGTH_SHORT).show();
			return;
		}
		adapter.reload();
		RecyclerView recycler = findViewById(R.id.grid_recycler);
		recycler.scrollToPosition(adapter.ids().indexOf(n));
	}

	// ------------------------------------------------------------------ adapter

	private final class SlotAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		static final int TYPE_TILE = 0;
		static final int TYPE_ADD = 1;
		private List<Integer> ids = new ArrayList<>();

		List<Integer> ids() {
			return new ArrayList<>(ids);
		}

		@SuppressLint("NotifyDataSetChanged")
		void reload() {
			ids = Slots.ids(GridActivity.this);
			notifyDataSetChanged();
		}

		void notifySlotChanged(int n) {
			int pos = ids.indexOf(n);
			if (pos >= 0) notifyItemChanged(pos);
		}

		@Override
		public int getItemViewType(int position) {
			return position < ids.size() ? TYPE_TILE : TYPE_ADD;
		}

		@Override
		public int getItemCount() {
			return ids.size() + 1;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inflater = LayoutInflater.from(parent.getContext());
			if (viewType == TYPE_ADD) {
				return new AddHolder(inflater.inflate(R.layout.item_slot_add, parent, false));
			}
			return new TileHolder(inflater.inflate(R.layout.item_slot_tile, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			if (holder instanceof AddHolder) {
				Button b = ((AddHolder) holder).button;
				boolean full = ids.size() >= Slots.MAX;
				b.setEnabled(!full);
				b.setText(full ? getString(R.string.slot_max, Slots.MAX) : getString(R.string.slot_add));
				b.setOnClickListener(v -> addSlot());
				return;
			}
			((TileHolder) holder).bind(ids.get(position));
		}
	}

	private static final class AddHolder extends RecyclerView.ViewHolder {
		final Button button;

		AddHolder(View v) {
			super(v);
			button = (Button) v;
		}
	}

	private final class TileHolder extends RecyclerView.ViewHolder {
		final View root;
		final ImageView preview;
		final TextView placeholder;
		final TextView number;
		final TextView proxy;
		final TextView title;
		final TextView status;
		final Button start;
		final Button stop;
		final Button menu;

		TileHolder(View v) {
			super(v);
			root = v.findViewById(R.id.tile_root);
			preview = v.findViewById(R.id.tile_preview);
			placeholder = v.findViewById(R.id.tile_placeholder);
			number = v.findViewById(R.id.tile_number);
			proxy = v.findViewById(R.id.tile_proxy);
			title = v.findViewById(R.id.tile_title);
			status = v.findViewById(R.id.tile_status);
			start = v.findViewById(R.id.tile_start);
			stop = v.findViewById(R.id.tile_stop);
			menu = v.findViewById(R.id.tile_menu);
		}

		void bind(int n) {
			Context ctx = GridActivity.this;
			SlotState s = state(n);
			boolean hasGame = Slots.path(ctx, n) != null;
			number.setText(getString(R.string.slot_number, n));
			title.setText(Slots.title(ctx, n));
			if (s.running) {
				status.setText(R.string.slot_status_running);
				status.setTextColor(ContextCompat.getColor(ctx, R.color.tile_status_running));
				root.setBackgroundResource(R.drawable.bg_slot_tile_running);
			} else {
				status.setText(hasGame ? R.string.slot_status_ready : R.string.slot_status_empty);
				status.setTextColor(ContextCompat.getColor(ctx, R.color.tile_status));
				root.setBackgroundResource(R.drawable.bg_slot_tile);
			}
			if (s.preview != null && hasGame) {
				preview.setImageBitmap(s.preview);
				placeholder.setVisibility(View.GONE);
			} else {
				preview.setImageDrawable(null);
				placeholder.setVisibility(View.VISIBLE);
			}
			proxy.setVisibility(s.proxy && hasGame ? View.VISIBLE : View.GONE);
			start.setEnabled(true);
			stop.setEnabled(s.running);
			start.setOnClickListener(v -> onStartClicked(n));
			stop.setOnClickListener(v -> stopSlot(n, null));
			menu.setOnClickListener(v -> showTileMenu(v, n));
			preview.setOnClickListener(v -> {
				if (s.running) onStartClicked(n);
			});
		}
	}
}
