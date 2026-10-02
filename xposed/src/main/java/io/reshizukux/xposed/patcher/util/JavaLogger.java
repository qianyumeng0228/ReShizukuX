// This file is part of LSPatch (https://github.com/JingMatrix/LSPatch)
// Copyright (C) 2023 LSPatch contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.reshizukux.xposed.patcher.util;

import android.util.Log;

/**
 * Routes patcher log lines into logcat instead of stdout/stderr. On an Android runtime
 * System.out.println is either dropped or merged into the process' stdout (invisible from
 * `adb logcat`), so patch progress/errors never reached a device-side log.
 */
public class JavaLogger extends Logger {

    private static final String TAG = "LSPatch";

    @Override
    public void d(String msg) {
        if (verbose) Log.d(TAG, msg);
    }

    @Override
    public void i(String msg) {
        Log.i(TAG, msg);
    }

    @Override
    public void e(String msg) {
        Log.e(TAG, msg);
    }
}
