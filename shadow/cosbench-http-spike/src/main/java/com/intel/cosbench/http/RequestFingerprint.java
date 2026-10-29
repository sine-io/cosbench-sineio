package com.intel.cosbench.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String sha256(String command, byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(command.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(payload == null ? new byte[0] : payload);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 0xf, 16));
                result.append(Character.forDigit(value & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
