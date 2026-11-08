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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.example.j2meslots.Slots;
import com.example.j2meslots.proxy.ProxyConfig;
import com.example.j2meslots.proxy.ProxyStore;
import com.example.j2meslots.proxy.ProxyTester;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.playsoftware.j2meloader.R;

/**
 * Settings → Proxy: mode (off / global / per slot), editor for the global or a slot config,
 * URI import/export, connection test, and the per-slot "allow legacy certificates" option.
 */
public class ProxySettingsActivity extends AppCompatActivity {
	private static final ProxyConfig.Type[] TYPES = {
			ProxyConfig.Type.HTTP, ProxyConfig.Type.HTTPS_CONNECT, ProxyConfig.Type.SOCKS5};

	private final ExecutorService io = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private ProxyStore store;
	private final List<Integer> targets = new ArrayList<>(); // 0 = global
	private int targetPos;
	private boolean binding;

	private RadioGroup modeGroup;
	private Spinner targetSpinner;
	private SwitchCompat slotEnabled;
	private TextView perSlotNote;
	private Spinner typeSpinner;
	private EditText host;
	private EditText port;
	private EditText user;
	private EditText password;
	private EditText bypass;
	private EditText testUrl;
	private Button testButton;
	private TextView testResult;
	private SwitchCompat legacyCerts;

	/** Result of reading the editor: valid (config may be null = "no proxy") or invalid. */
	private static final class Fields {
		final boolean valid;
		final ProxyConfig config;

		Fields(boolean valid, ProxyConfig config) {
			this.valid = valid;
			this.config = config;
		}
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		getDelegate().setLocalNightMode(Slots.dark(this)
				? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_proxy_settings);
		Toolbar toolbar = findViewById(R.id.proxy_toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		setTitle(R.string.proxy_title);
		store = ProxyStore.get(this);

		modeGroup = findViewById(R.id.proxy_mode_group);
		targetSpinner = findViewById(R.id.proxy_target);
		slotEnabled = findViewById(R.id.proxy_slot_enabled);
		perSlotNote = findViewById(R.id.proxy_per_slot_note);
		typeSpinner = findViewById(R.id.proxy_type);
		host = findViewById(R.id.proxy_host);
		port = findViewById(R.id.proxy_port);
		user = findViewById(R.id.proxy_user);
		password = findViewById(R.id.proxy_password);
		bypass = findViewById(R.id.proxy_bypass);
		testUrl = findViewById(R.id.proxy_test_url);
		testButton = findViewById(R.id.proxy_test);
		testResult = findViewById(R.id.proxy_test_result);
		legacyCerts = findViewById(R.id.proxy_legacy_certs);
		findViewById(R.id.proxy_not_encrypted).setVisibility(store.isEncrypted() ? View.GONE : View.VISIBLE);

		switch (store.mode()) {
			case GLOBAL:
				modeGroup.check(R.id.proxy_mode_global);
				break;
			case PER_SLOT:
				modeGroup.check(R.id.proxy_mode_per_slot);
				break;
			default:
				modeGroup.check(R.id.proxy_mode_off);
		}
		modeGroup.setOnCheckedChangeListener((g, id) -> {
			ProxyStore.Mode m = id == R.id.proxy_mode_global ? ProxyStore.Mode.GLOBAL
					: id == R.id.proxy_mode_per_slot ? ProxyStore.Mode.PER_SLOT : ProxyStore.Mode.OFF;
			store.setMode(m);
			if (m == ProxyStore.Mode.GLOBAL && targetPos != 0) targetSpinner.setSelection(0);
			updateNotes();
		});

		ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, new String[]{"HTTP", "HTTPS-CONNECT", "SOCKS5"});
		typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		typeSpinner.setAdapter(typeAdapter);

		targets.add(0);
		List<String> labels = new ArrayList<>();
		labels.add(getString(R.string.proxy_target_global));
		for (int n : Slots.ids(this)) {
			targets.add(n);
			labels.add(getString(R.string.slot_default_name, n) + " · " + Slots.title(this, n));
		}
		ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, labels);
		targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		targetSpinner.setAdapter(targetAdapter);
		targetPos = store.mode() == ProxyStore.Mode.PER_SLOT && targets.size() > 1 ? 1 : 0;
		targetSpinner.setSelection(targetPos);
		targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (position == targetPos) return;
				if (!saveCurrent(false)) {
					targetSpinner.setSelection(targetPos);
					return;
				}
				targetPos = position;
				loadTarget();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		legacyCerts.setOnCheckedChangeListener((b, checked) -> {
			if (binding) return;
			int n = targets.get(targetPos);
			if (n == 0) {
				binding = true;
				legacyCerts.setChecked(false);
				binding = false;
				Toast.makeText(this, R.string.proxy_select_slot_first, Toast.LENGTH_SHORT).show();
				return;
			}
			if (!checked) {
				Slots.setLegacyCerts(this, n, false);
				return;
			}
			new AlertDialog.Builder(this)
					.setTitle(R.string.proxy_legacy_title)
					.setMessage(R.string.proxy_legacy_warning)
					.setPositiveButton(R.string.proxy_legacy_enable, (d, w) -> Slots.setLegacyCerts(this, n, true))
					.setNegativeButton(R.string.action_cancel, (d, w) -> {
						binding = true;
						legacyCerts.setChecked(false);
						binding = false;
					})
					.setCancelable(false)
					.show();
		});
		slotEnabled.setOnCheckedChangeListener((b, c) -> updateNotes());

		testUrl.setText(store.testUrl());
		findViewById(R.id.proxy_import).setOnClickListener(v -> showImport());
		findViewById(R.id.proxy_export).setOnClickListener(v -> showExport());
		findViewById(R.id.proxy_save).setOnClickListener(v -> saveCurrent(true));
		testButton.setOnClickListener(v -> runTest());
		loadTarget();
	}

	@Override
	protected void onPause() {
		saveCurrent(false);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		io.shutdownNow();
		super.onDestroy();
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void loadTarget() {
		binding = true;
		int n = targets.get(targetPos);
		ProxyConfig c = n == 0 ? store.global() : store.slot(n);
		fill(c);
		slotEnabled.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
		slotEnabled.setChecked(n != 0 && store.slotEnabled(n));
		legacyCerts.setChecked(n != 0 && Slots.legacyCerts(this, n));
		testResult.setText("");
		binding = false;
		updateNotes();
	}

	private void fill(@Nullable ProxyConfig c) {
		host.setError(null);
		port.setError(null);
		if (c == null) {
			typeSpinner.setSelection(2);
			host.setText("");
			port.setText("");
			user.setText("");
			password.setText("");
			bypass.setText("");
			return;
		}
		for (int i = 0; i < TYPES.length; i++) {
			if (TYPES[i] == c.type) typeSpinner.setSelection(i);
		}
		host.setText(c.host);
		port.setText(String.valueOf(c.port));
		user.setText(c.user);
		password.setText(c.password);
		bypass.setText(c.bypass);
	}

	private void updateNotes() {
		int n = targets.get(targetPos);
		perSlotNote.setVisibility(n != 0 && store.mode() != ProxyStore.Mode.PER_SLOT
				? View.VISIBLE : View.GONE);
	}

	private Fields readFields() {
		host.setError(null);
		port.setError(null);
		String h = host.getText().toString().trim();
		String p = port.getText().toString().trim();
		String u = user.getText().toString();
		String pw = password.getText().toString();
		String b = bypass.getText().toString();
		if (h.isEmpty() && p.isEmpty() && u.isEmpty() && pw.isEmpty()) {
			return new Fields(true, null);
		}
		int portNum = -1;
		try {
			portNum = Integer.parseInt(p);
		} catch (NumberFormatException ignored) {
		}
		ProxyConfig c = new ProxyConfig(TYPES[Math.max(0, typeSpinner.getSelectedItemPosition())],
				h, portNum, u, pw, b);
		String err = c.validate();
		if (err != null) {
			if ("port".equals(err)) {
				port.setError(getString(R.string.proxy_invalid_port));
				port.requestFocus();
			} else {
				host.setError(getString(R.string.proxy_invalid_host));
				host.requestFocus();
			}
			return new Fields(false, null);
		}
		return new Fields(true, c);
	}

	private boolean saveCurrent(boolean showToast) {
		Fields f = readFields();
		if (!f.valid) return false;
		int n = targets.get(targetPos);
		if (n == 0) {
			store.setGlobal(f.config);
		} else {
			store.setSlot(n, f.config);
			store.setSlotEnabled(n, slotEnabled.isChecked() && f.config != null);
		}
		String url = testUrl.getText().toString().trim();
		if (!url.isEmpty()) store.setTestUrl(url);
		if (showToast) Toast.makeText(this, R.string.proxy_saved, Toast.LENGTH_LONG).show();
		return true;
	}

	private void showImport() {
		final EditText edit = new EditText(this);
		edit.setSingleLine(true);
		edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		edit.setHint(R.string.proxy_import_hint);
		new AlertDialog.Builder(this)
				.setTitle(R.string.proxy_import)
				.setView(wrap(edit))
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					try {
						ProxyConfig c = ProxyConfig.parseUri(edit.getText().toString(),
								bypass.getText().toString());
						fill(c);
					} catch (IllegalArgumentException e) {
						Toast.makeText(this, getString(R.string.proxy_import_invalid, e.getMessage()),
								Toast.LENGTH_LONG).show();
					}
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void showExport() {
		Fields f = readFields();
		if (!f.valid) return;
		if (f.config == null) {
			host.setError(getString(R.string.proxy_invalid_host));
			return;
		}
		final ProxyConfig c = f.config;
		final TextView text = new TextView(this);
		text.setTextIsSelectable(true);
		text.setTextSize(15);
		text.setTextColor(ContextCompat.getColor(this, R.color.tile_title));
		text.setText(c.toUri(false));
		final CheckBox withPass = new CheckBox(this);
		withPass.setText(R.string.proxy_export_with_password);
		withPass.setTextColor(ContextCompat.getColor(this, R.color.tile_title));
		withPass.setEnabled(!c.password.isEmpty());
		withPass.setOnCheckedChangeListener((b, checked) -> text.setText(c.toUri(checked)));
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.addView(text);
		box.addView(withPass);
		new AlertDialog.Builder(this)
				.setTitle(R.string.proxy_export)
				.setView(wrap(box))
				.setPositiveButton(R.string.proxy_copy, (d, w) -> {
					ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					if (cm != null) {
						cm.setPrimaryClip(ClipData.newPlainText("proxy", text.getText()));
						Toast.makeText(this, R.string.proxy_copied, Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton(R.string.action_close, null)
				.show();
	}

	private View wrap(View v) {
		LinearLayout box = new LinearLayout(this);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));
		return box;
	}

	private void runTest() {
		Fields f = readFields();
		if (!f.valid) return;
		if (f.config == null) {
			host.setError(getString(R.string.proxy_invalid_host));
			host.requestFocus();
			return;
		}
		final ProxyConfig c = f.config;
		final String url = testUrl.getText().toString().trim().isEmpty()
				? ProxyStore.DEFAULT_TEST_URL : testUrl.getText().toString().trim();
		int n = targets.get(targetPos);
		final boolean trustAll = n != 0 && Slots.legacyCerts(this, n);
		testButton.setEnabled(false);
		testResult.setTextColor(ContextCompat.getColor(this, R.color.tile_status));
		testResult.setText(R.string.proxy_testing);
		io.execute(() -> {
			ProxyTester.Result r = ProxyTester.test(c, url, trustAll);
			handler.post(() -> {
				if (isDestroyed()) return;
				testButton.setEnabled(true);
				if (r.ok) {
					testResult.setTextColor(ContextCompat.getColor(this, R.color.tile_status_running));
					testResult.setText(getString(R.string.proxy_test_ok, (int) r.millis, r.httpCode));
				} else {
					testResult.setTextColor(ContextCompat.getColor(this, R.color.warning_text));
					testResult.setText(getString(R.string.proxy_test_fail, r.error));
				}
			});
		});
	}
}
