// This file is part of LSPatch (https://github.com/JingMatrix/LSPatch)
// Copyright (C) 2023 LSPatch contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.reshizukux.xposed.patcher.util;

public class JavaLogger extends Logger {

    @Override
    public void d(String msg) {
        if (verbose) System.out.println(msg);
    }

    @Override
    public void i(String msg) {
        System.out.println(msg);
    }

    @Override
    public void e(String msg) {
        System.err.println(msg);
    }
}
