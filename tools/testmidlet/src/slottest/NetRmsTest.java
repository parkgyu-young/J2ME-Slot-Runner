/*
 * Copyright 2026 J2ME Slot Runner contributors
 * Licensed under the Apache License, Version 2.0
 *
 * Acceptance-test MIDlet for J2ME Slot Runner (MIDP 2.0 / CLDC 1.1 APIs only):
 *  - RMS launch counter     -> two slots running the same jar keep separate saves
 *  - tick counter (1/s)     -> keeps counting while the screen is off in background mode
 *  - HTTP / socket commands -> traffic visible in the proxy log
 * JAD/manifest properties: Test-URL (default http://example.com/), Test-Socket (host:port).
 */
package slottest;

import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.SocketConnection;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

public class NetRmsTest extends MIDlet implements CommandListener {
	private final Command exitCmd = new Command("Exit", Command.EXIT, 1);
	private final Command httpCmd = new Command("HTTP test", Command.SCREEN, 1);
	private final Command socketCmd = new Command("Socket test", Command.SCREEN, 2);
	private final Command resetCmd = new Command("Reset RMS", Command.SCREEN, 3);
	private Form form;
	private StringItem launches;
	private StringItem ticks;
	private StringItem net;
	private volatile boolean running;
	private long startedAt;

	public void startApp() {
		if (form == null) {
			form = new Form("NetRmsTest");
			launches = new StringItem("RMS launches: ", "?");
			ticks = new StringItem("Ticks: ", "0");
			net = new StringItem("Net: ", "-");
			form.append(launches);
			form.append(ticks);
			form.append(net);
			form.addCommand(exitCmd);
			form.addCommand(httpCmd);
			form.addCommand(socketCmd);
			form.addCommand(resetCmd);
			form.setCommandListener(this);
			launches.setText(String.valueOf(bumpCounter(false)));
			startedAt = System.currentTimeMillis();
			running = true;
			new Thread(new Runnable() {
				public void run() {
					tickLoop();
				}
			}).start();
		}
		Display.getDisplay(this).setCurrent(form);
	}

	// Kept in separate small methods: the emulator's JAR->DEX converter miscomputes the operand
	// stack of loops that contain a try/catch together with long arithmetic.
	private void tickLoop() {
		int n = 0;
		while (running && sleepOneSecond()) {
			n++;
			showTicks(n);
		}
	}

	private static boolean sleepOneSecond() {
		try {
			Thread.sleep(1000);
			return true;
		} catch (InterruptedException e) {
			return false;
		}
	}

	private void showTicks(int n) {
		long secs = (System.currentTimeMillis() - startedAt) / 1000;
		ticks.setText(n + " (wall " + secs + " s)");
	}

	public void pauseApp() {
		// Without background mode J2ME-Loader pauses here; ticks keep counting only in background mode
	}

	public void destroyApp(boolean unconditional) {
		running = false;
	}

	private int bumpCounter(boolean reset) {
		RecordStore rs = null;
		try {
			rs = RecordStore.openRecordStore("launches", true);
			int value = 0;
			if (rs.getNumRecords() > 0) {
				byte[] b = rs.getRecord(1);
				value = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
			}
			value = reset ? 0 : value + 1;
			byte[] out = {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
			if (rs.getNumRecords() > 0) {
				rs.setRecord(1, out, 0, 4);
			} else {
				rs.addRecord(out, 0, 4);
			}
			return value;
		} catch (Exception e) {
			return -1;
		} finally {
			if (rs != null) {
				try {
					rs.closeRecordStore();
				} catch (Exception ignored) {
				}
			}
		}
	}

	public void commandAction(Command c, Displayable d) {
		if (c == exitCmd) {
			destroyApp(true);
			notifyDestroyed();
		} else if (c == resetCmd) {
			launches.setText(String.valueOf(bumpCounter(true)));
		} else if (c == httpCmd) {
			net.setText("HTTP...");
			new Thread(new Runnable() {
				public void run() {
					net.setText(httpTest());
				}
			}).start();
		} else if (c == socketCmd) {
			net.setText("Socket...");
			new Thread(new Runnable() {
				public void run() {
					net.setText(socketTest());
				}
			}).start();
		}
	}

	private String prop(String key, String def) {
		String v = getAppProperty(key);
		return v == null || v.length() == 0 ? def : v.trim();
	}

	private String httpTest() {
		String url = prop("Test-URL", "http://example.com/");
		long t0 = System.currentTimeMillis();
		HttpConnection c = null;
		InputStream in = null;
		try {
			c = (HttpConnection) Connector.open(url);
			c.setRequestProperty("User-Agent", "NetRmsTest/1.0 (J2ME Slot Runner)");
			int code = c.getResponseCode();
			in = c.openInputStream();
			byte[] buf = new byte[48];
			int r = in.read(buf);
			String head = r > 0 ? new String(buf, 0, r) : "";
			return "HTTP " + code + " in " + (System.currentTimeMillis() - t0) + " ms: " + head;
		} catch (Exception e) {
			return "HTTP error: " + e.toString();
		} finally {
			try {
				if (in != null) in.close();
				if (c != null) c.close();
			} catch (Exception ignored) {
			}
		}
	}

	private String socketTest() {
		String target = prop("Test-Socket", "example.com:80");
		String host = target.substring(0, target.lastIndexOf(':'));
		long t0 = System.currentTimeMillis();
		SocketConnection s = null;
		try {
			s = (SocketConnection) Connector.open("socket://" + target);
			OutputStream out = s.openOutputStream();
			out.write(("HEAD / HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes());
			out.flush();
			InputStream in = s.openInputStream();
			StringBuffer sb = new StringBuffer();
			int ch;
			while ((ch = in.read()) != -1 && ch != '\n' && sb.length() < 64) sb.append((char) ch);
			return "Socket " + (System.currentTimeMillis() - t0) + " ms: " + sb.toString().trim();
		} catch (Exception e) {
			return "Socket error: " + e.toString();
		} finally {
			try {
				if (s != null) s.close();
			} catch (Exception ignored) {
			}
		}
	}
}
