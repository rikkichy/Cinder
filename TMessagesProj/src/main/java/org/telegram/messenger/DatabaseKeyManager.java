package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class DatabaseKeyManager {

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEYSTORE_ALIAS_PREFIX = "cinder_dek_wrapper_";
    private static final int DEK_SIZE_BYTES = 32;
    private static final int GCM_IV_SIZE = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int PBKDF2_SALT_SIZE = 16;

    private static final String PREF_ENCRYPTED_DEK = "encrypted_dek";
    private static final String PREF_DEK_IV = "dek_iv";
    private static final String PREF_PBKDF2_SALT = "pbkdf2_salt";
    private static final String PREF_KEY_MODE = "key_mode";

    private static final String MODE_KEYSTORE = "keystore";
    private static final String MODE_PASSCODE = "passcode";

    private static final DatabaseKeyManager[] instances = new DatabaseKeyManager[UserConfig.MAX_ACCOUNT_COUNT];

    private final int accountNum;
    private byte[] cachedDek;

    public static DatabaseKeyManager getInstance(int account) {
        DatabaseKeyManager instance = instances[account];
        if (instance == null) {
            synchronized (DatabaseKeyManager.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DatabaseKeyManager(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DatabaseKeyManager(int account) {
        this.accountNum = account;
    }

    public synchronized byte[] getDek() {
        if (cachedDek != null) {
            return cachedDek.clone();
        }

        SharedPreferences prefs = getPrefs();
        String mode = prefs.getString(PREF_KEY_MODE, null);

        if (mode == null) {
            cachedDek = generateAndStoreDek();
            return cachedDek.clone();
        }

        try {
            byte[] encryptedDek = Base64.decode(prefs.getString(PREF_ENCRYPTED_DEK, ""), Base64.NO_WRAP);
            byte[] iv = Base64.decode(prefs.getString(PREF_DEK_IV, ""), Base64.NO_WRAP);

            if (MODE_KEYSTORE.equals(mode)) {
                cachedDek = unwrapWithKeystore(encryptedDek, iv);
            }
        } catch (Exception e) {
            FileLog.e(e);
            cachedDek = generateAndStoreDek();
        }

        if (cachedDek == null) {
            cachedDek = generateAndStoreDek();
        }

        return cachedDek.clone();
    }

    public synchronized byte[] getDekWithPasscode(byte[] passcodeBytes) {
        if (cachedDek != null) {
            return cachedDek.clone();
        }

        SharedPreferences prefs = getPrefs();
        String mode = prefs.getString(PREF_KEY_MODE, null);

        if (!MODE_PASSCODE.equals(mode)) {
            return getDek();
        }

        try {
            byte[] encryptedDek = Base64.decode(prefs.getString(PREF_ENCRYPTED_DEK, ""), Base64.NO_WRAP);
            byte[] iv = Base64.decode(prefs.getString(PREF_DEK_IV, ""), Base64.NO_WRAP);
            byte[] salt = Base64.decode(prefs.getString(PREF_PBKDF2_SALT, ""), Base64.NO_WRAP);

            cachedDek = unwrapWithPasscode(encryptedDek, iv, salt, passcodeBytes);
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cachedDek != null) {
            return cachedDek.clone();
        }
        return null;
    }

    public synchronized void onPasscodeSet(byte[] passcodeBytes) {
        if (cachedDek == null) {
            return;
        }
        byte[] salt = null;
        byte[] kek = null;
        try {
            salt = new byte[PBKDF2_SALT_SIZE];
            new SecureRandom().nextBytes(salt);
            kek = deriveKek(passcodeBytes, salt);

            byte[] iv = new byte[GCM_IV_SIZE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encryptedDek = cipher.doFinal(cachedDek);

            getPrefs().edit()
                    .putString(PREF_ENCRYPTED_DEK, Base64.encodeToString(encryptedDek, Base64.NO_WRAP))
                    .putString(PREF_DEK_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(PREF_PBKDF2_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(PREF_KEY_MODE, MODE_PASSCODE)
                    .apply();

            deleteKeystoreKey();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (kek != null) Arrays.fill(kek, (byte) 0);
        }
    }

    public synchronized void onPasscodeRemoved() {
        if (cachedDek == null) {
            return;
        }
        try {
            wrapAndStoreWithKeystore(cachedDek);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public synchronized void lockDek() {
        if (cachedDek != null) {
            Arrays.fill(cachedDek, (byte) 0);
            cachedDek = null;
        }
    }

    public synchronized boolean isLocked() {
        return cachedDek == null;
    }

    public boolean isPasscodeMode() {
        return MODE_PASSCODE.equals(getPrefs().getString(PREF_KEY_MODE, null));
    }

    private byte[] generateAndStoreDek() {
        byte[] dek = new byte[DEK_SIZE_BYTES];
        new SecureRandom().nextBytes(dek);
        try {
            wrapAndStoreWithKeystore(dek);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return dek;
    }

    private void wrapAndStoreWithKeystore(byte[] dek) throws Exception {
        SecretKey keystoreKey = getOrCreateKeystoreKey();

        byte[] iv = new byte[GCM_IV_SIZE];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encryptedDek = cipher.doFinal(dek);

        getPrefs().edit()
                .putString(PREF_ENCRYPTED_DEK, Base64.encodeToString(encryptedDek, Base64.NO_WRAP))
                .putString(PREF_DEK_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_KEY_MODE, MODE_KEYSTORE)
                .remove(PREF_PBKDF2_SALT)
                .apply();
    }

    private byte[] unwrapWithKeystore(byte[] encryptedDek, byte[] iv) throws Exception {
        SecretKey keystoreKey = getOrCreateKeystoreKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(encryptedDek);
    }

    private byte[] unwrapWithPasscode(byte[] encryptedDek, byte[] iv, byte[] salt, byte[] passcodeBytes) throws Exception {
        byte[] kek = null;
        try {
            kek = deriveKek(passcodeBytes, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(encryptedDek);
        } finally {
            if (kek != null) Arrays.fill(kek, (byte) 0);
        }
    }

    private byte[] deriveKek(byte[] passcodeBytes, byte[] salt) throws Exception {
        char[] passcodeChars = new char[passcodeBytes.length];
        for (int i = 0; i < passcodeBytes.length; i++) {
            passcodeChars[i] = (char) (passcodeBytes[i] & 0xFF);
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(passcodeChars, salt, PBKDF2_ITERATIONS, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return key;
        } finally {
            Arrays.fill(passcodeChars, '\0');
        }
    }

    private SecretKey getOrCreateKeystoreKey() throws Exception {
        String alias = KEYSTORE_ALIAS_PREFIX + accountNum;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(alias)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null)).getSecretKey();
        }

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        kg.init(spec);
        return kg.generateKey();
    }

    private void deleteKeystoreKey() {
        try {
            String alias = KEYSTORE_ALIAS_PREFIX + accountNum;
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("dek_" + accountNum, 0);
    }
}
