package com.canvasplus;

import android.app.Application;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.util.Log;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Batches module events off the host app's UI thread for delivery to the settings app. */
final class DiagnosticLog {
    static final String ACTION_RECORD = "com.canvasplus.RECORD_DIAGNOSTICS";
    static final String ACTION_FLUSH = "com.canvasplus.FLUSH_DIAGNOSTICS";
    static final int RESULT_STORED = 1;
    private static final String MODULE_PACKAGE = "com.canvasplus";
    private static final List<String> SOURCES = List.of(Settings.CANVAS_PACKAGE,
            "com.coloros.smartsidebar", "tv.danmaku.bili");
    static final String EXTRA_EVENTS = "events";
    static final int MAX_BATCH_CHARS = 48 * 1024;
    private static final int MAX_EVENTS = 256;
    private static final int MAX_EVENT_CHARS = 700;

    private static final ArrayDeque<String> pending = new ArrayDeque<>();
    private static Context sender;
    private static ScheduledExecutorService worker;
    private static boolean flushScheduled;
    private static boolean enabled;
    private static int enabledRevision;
    private static int dropped;

    private DiagnosticLog() {}

    static boolean accepts(String packageName) {
        return packageName != null && SOURCES.contains(packageName);
    }

    static synchronized void start(Context context) {
        if (sender != null) return;
        sender = context;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CanvasPlus-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (ACTION_FLUSH.equals(intent.getAction()) && MODULE_PACKAGE.equals(getSentFromPackage())) {
                        setEnabled(intent.getBooleanExtra(Settings.DIAGNOSTICS_ENABLED, false));
                        scheduleFlush(0);
                    }
                }
            }, new IntentFilter(ACTION_FLUSH), Context.RECEIVER_EXPORTED);
        } catch (RuntimeException error) {
            Log.w("CanvasPlus", "Diagnostic refresh request unavailable", error);
        }
        String version = "unknown";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        record("process started: " + Application.getProcessName() + " version=" + version);
    }

    static synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        enabledRevision++;
        pending.clear();
        dropped = 0;
        if (enabled) record("diagnostic capture enabled");
    }

    static synchronized void record(String message) {
        if (!enabled) return;
        String line = String.valueOf(message).replace('\r', ' ').replace('\n', ' ');
        if (line.length() > MAX_EVENT_CHARS) line = line.substring(0, MAX_EVENT_CHARS) + "…";
        pending.addLast(Instant.now() + " " + line + "\n");
        trimPending();
        scheduleFlush(500);
    }

    static void requestPending(Context context) {
        for (String source : SOURCES) {
            try {
                context.sendBroadcast(new Intent(ACTION_FLUSH).setPackage(source)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(Settings.DIAGNOSTICS_ENABLED, Settings.diagnosticsEnabled(context)),
                        null, identityOptions());
            } catch (RuntimeException error) {
                Log.w("CanvasPlus", "Could not request pending diagnostics from " + source, error);
            }
        }
    }

    private static android.os.Bundle identityOptions() {
        return BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE).toBundle();
    }

    private static void trimPending() {
        while (pending.size() > MAX_EVENTS) {
            pending.removeFirst();
            dropped++;
        }
    }

    private static synchronized void scheduleFlush(long delayMs) {
        if (worker == null || flushScheduled || pending.isEmpty()) return;
        flushScheduled = true;
        worker.schedule(DiagnosticLog::flush, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void flush() {
        StringBuilder batch = new StringBuilder();
        List<String> sending = new ArrayList<>();
        int droppedBefore;
        int revision;
        synchronized (DiagnosticLog.class) {
            if (!enabled || pending.isEmpty()) {
                flushScheduled = false;
                return;
            }
            revision = enabledRevision;
            droppedBefore = dropped;
            if (dropped > 0) batch.append("[dropped ").append(dropped).append(" older events]\n");
            while (!pending.isEmpty() && batch.length() + pending.getFirst().length() <= MAX_BATCH_CHARS) {
                String event = pending.removeFirst();
                batch.append(event);
                sending.add(event);
            }
            dropped = 0;
        }
        try {
            Intent intent = new Intent(ACTION_RECORD)
                    .setClassName(MODULE_PACKAGE, "com.canvasplus.DiagnosticLogReceiver")
                    .putExtra(EXTRA_EVENTS, batch.toString());
            sender.sendOrderedBroadcast(intent, null, identityOptions(), new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    finishFlush(sending, droppedBefore, revision, getResultCode() == RESULT_STORED);
                }
            }, null, 0, null, null);
        } catch (RuntimeException error) {
            finishFlush(sending, droppedBefore, revision, false);
            Log.w("CanvasPlus", "Diagnostic delivery failed", error);
        }
    }

    private static synchronized void finishFlush(List<String> sending, int droppedBefore,
                                                 int revision, boolean stored) {
        flushScheduled = false;
        if (revision != enabledRevision) {
            scheduleFlush(0);
            return;
        }
        if (!stored) {
            for (int index = sending.size() - 1; index >= 0; index--) pending.addFirst(sending.get(index));
            dropped += droppedBefore;
            trimPending();
            return;
        }
        scheduleFlush(0);
    }
}
