// This file is part of LSPatch (https://github.com/JingMatrix/LSPatch)
// Copyright (C) 2023 LSPatch contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.reshizukux.xposed.share;

/**
 * Built-time version of the LSPatch shared config.
 *
 * Upstream this class is generated from a template whose {@code ${...}} tokens are filled in by the
 * build (see share/java/src/template). Phase 1 ships a concrete hand-written stand-in so
 * {@link PatchConfig} and {@code ApkPatcher} compile and link; the values here are placeholders and
 * will be wired to the real loader metadata when the loader payload is integrated (a later phase).
 */
public class LSPConfig {

    public static final LSPConfig instance;

    public int API_CODE;
    public int VERSION_CODE;
    public String VERSION_NAME;
    public int CORE_VERSION_CODE;
    public String CORE_VERSION_NAME;
    public String CORE_VERSION_HASH;

    private LSPConfig() {
    }

    static {
        instance = new LSPConfig();
        instance.API_CODE = 0;
        instance.VERSION_CODE = 0;
        // xposed 是 library 且未开启 BuildConfig，直接硬编码与根版本号一致；不再使用 "phase1-placeholder"。
        instance.VERSION_NAME = "14.0.0-beta2";
        instance.CORE_VERSION_CODE = 0;
        instance.CORE_VERSION_NAME = "phase1-placeholder";
        instance.CORE_VERSION_HASH = "";
    }
}
