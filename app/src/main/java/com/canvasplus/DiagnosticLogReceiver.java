package com.canvasplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Accepts write-only batches from the three packages instrumented by the diagnostic APK. */
public final class DiagnosticLogReceiver extends BroadcastReceiver {
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        if (!DiagnosticLog.ACTION_RECORD.equals(intent.getAction()) || !isOrderedBroadcast()) return;
        String source = getSentFromPackage();
        if (!DiagnosticLog.accepts(source) || !Settings.diagnosticsEnabled(context)) return;
        String events = intent.getStringExtra(DiagnosticLog.EXTRA_EVENTS);
        if (events == null || events.isEmpty() || events.length() > DiagnosticLog.MAX_BATCH_CHARS) return;

        DiagnosticLogStore store = new DiagnosticLogStore(context.getFilesDir().toPath());
        PendingResult result = goAsync();
        writer.execute(() -> {
            try {
                if (!Settings.diagnosticsEnabled(context)) return;
                store.append("[" + source + "]\n" + events);
                result.setResultCode(DiagnosticLog.RESULT_STORED);
            } catch (IOException error) {
                Log.w("CanvasPlus", "Diagnostic storage failed", error);
            } finally {
                result.finish();
            }
        });
    }
}
