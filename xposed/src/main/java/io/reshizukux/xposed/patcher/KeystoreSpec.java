// This file is part of LSPatch (https://github.com/JingMatrix/LSPatch)
// Copyright (C) 2023 LSPatch contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.reshizukux.xposed.patcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * The key a patched apk is signed with.
 *
 * Replaces a {@code List<String>} of four positional strings read back as {@code get(0)} through
 * {@code get(3)} -- which never said which was the alias and which the alias password, and let a
 * caller supply three or five of them without anything noticing until the signer failed.
 *
 * The built-in keystore ships inside the patcher's own jar, which is why the file is allowed to be
 * absent: it is not a missing value but a different source.
 */
public final class KeystoreSpec {

    /** Where the bundled keystore lives inside this jar. */
    private static final String BUILT_IN_RESOURCE = "assets/keystore";

    private static final String DEFAULT_PASSWORD = "123456";
    private static final String DEFAULT_ALIAS = "key0";
    private static final String DEFAULT_ALIAS_PASSWORD = "123456";

    private final File file;
    private final String password;
    private final String alias;
    private final String aliasPassword;

    private KeystoreSpec(File file, String password, String alias, String aliasPassword) {
        this.file = file;
        this.password = password;
        this.alias = alias;
        this.aliasPassword = aliasPassword;
    }

    /** The keystore bundled with the patcher, with its well-known credentials. */
    public static KeystoreSpec builtIn() {
        return new KeystoreSpec(null, DEFAULT_PASSWORD, DEFAULT_ALIAS, DEFAULT_ALIAS_PASSWORD);
    }

    public static KeystoreSpec of(File file, String password, String alias, String aliasPassword) {
        if (file == null) return builtIn();
        return new KeystoreSpec(file, password, alias, aliasPassword);
    }

    public boolean isBuiltIn() {
        return file == null;
    }

    public String alias() {
        return alias;
    }

    public String aliasPassword() {
        return aliasPassword;
    }

    /** Opens the keystore bytes, from the jar's own resources or from disk. */
    InputStream open(ClassLoader loader) throws IOException {
        if (file == null) {
            InputStream is = loader.getResourceAsStream(BUILT_IN_RESOURCE);
            if (is == null) throw new PatchException("The built-in keystore is missing from this build");
            return is;
        }
        return new FileInputStream(file);
    }

    KeyStore load(ClassLoader loader) throws IOException {
        // The bundled keystore is a JKS file (magic FE ED FE ED). On a desktop JVM
        // KeyStore.getDefaultType() is "jks", but on Android it is "BKS", which
        // cannot read a JKS file and throws "Wrong version of key store". Try the
        // types in order until one accepts the bytes; the file format is fixed so
        // only the first attempt ever succeeds.
        String[] typesToTry = { "PKCS12", "BKS", "JKS" };
        Exception lastError = null;
        for (String type : typesToTry) {
            try {
                KeyStore keyStore = KeyStore.getInstance(type);
                try (InputStream is = open(loader)) {
                    keyStore.load(is, password.toCharArray());
                }
                return keyStore;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new PatchException("Failed to load the signing keystore", lastError);
    }

    @Override
    public String toString() {
        return isBuiltIn() ? "built-in keystore" : file.getPath();
    }
}
