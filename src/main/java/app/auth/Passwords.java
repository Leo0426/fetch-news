package app.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2 parameters travel with the salted hash so older accounts can still sign in. */
final class Passwords {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final String DUMMY_HASH =
            "pbkdf2-sha256$600000$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private Passwords() {}

    static String hash(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < 8 || length > 128 || password.isBlank()) {
            throw new IllegalArgumentException("密码需为 8–128 个字符，且不能全为空白。");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        try {
            return "pbkdf2-sha256$" + ITERATIONS + "$" + ENCODER.encodeToString(salt)
                    + "$" + ENCODER.encodeToString(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static boolean matches(String password, String hash) {
        if (password == null || password.isEmpty() || password.length() > 256) return false;
        String[] parts = (hash == null ? DUMMY_HASH : hash).split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] check = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || check.length != 32) return false;
            byte[] key = derive(password, salt, iterations);
            try {
                return MessageDigest.isEqual(key, check) && hash != null;
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 unavailable", unavailable);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
