package com.marvice.officetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Every token and password on the TV lives here, encrypted with an AES-256-GCM key held in the Android
 * Keystore (the key never leaves it). The ciphertext sits in app-private storage, which the release build
 * keeps out of backups and run-as. Nothing in this class logs a value.
 */
final class Vault {
    private static final String ALIAS = "officetv_vault";
    private static final String PREFS = "vault";
    private static final int IV_BYTES = 12, TAG_BITS = 128;

    private final SharedPreferences store;

    Vault(Context ctx) {
        store = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void put(String key, String value) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            store.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            throw new IllegalStateException("vault write failed: " + e.getClass().getSimpleName());
        }
    }

    /** The stored value, or null when absent or when it can no longer be decrypted (key gone). */
    synchronized String get(String key) {
        String b64 = store.getString(key, null);
        if (b64 == null) return null;
        try {
            byte[] in = Base64.decode(b64, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    synchronized void remove(String key) {
        store.edit().remove(key).commit();
    }

    /** Deletes every entry whose key starts with the prefix, e.g. one account's secrets. */
    synchronized void removePrefix(String prefix) {
        List<String> gone = new ArrayList<>();
        for (String k : store.getAll().keySet()) if (k.startsWith(prefix)) gone.add(k);
        SharedPreferences.Editor e = store.edit();
        for (String k : gone) e.remove(k);
        e.commit();
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
