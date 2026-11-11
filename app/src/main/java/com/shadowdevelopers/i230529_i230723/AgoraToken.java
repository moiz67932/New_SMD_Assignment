package com.shadowdevelopers.i230529_i230723;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.TreeMap;
import java.util.zip.Deflater;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Agora "AccessToken2" (version 007) for one RTC channel, built on the phone with the project's App Certificate.
 * This is a port of Agora's official token builder (RtcTokenBuilder2, publisher role, uid 0 = any user), checked
 * against the official library with fixed inputs.
 *
 * A project that has an App Certificate refuses users who join without a token. A production app would fetch the
 * token from its own server so the certificate never leaves the server; for this coursework app the certificate is
 * compiled in from the git-ignored agora.properties so calls work without running any extra server.
 */
public final class AgoraToken {
    private static final int PRIVILEGE_JOIN = 1;
    private static final int PRIVILEGE_PUBLISH_AUDIO = 2;
    private static final int PRIVILEGE_PUBLISH_VIDEO = 3;
    private static final int PRIVILEGE_PUBLISH_DATA = 4;
    /** Valid for 24 hours from now. */
    private static final long LIFETIME_SECONDS = 24L * 60 * 60;

    private AgoraToken() { }

    /** Returns a token for this channel, or null when no certificate is configured (Testing-mode projects need none). */
    public static String forChannel(String appId, String appCertificate, String channel) {
        if (appId == null || appId.isEmpty() || appCertificate == null || appCertificate.isEmpty()) return null;
        long issued = System.currentTimeMillis() / 1000L;
        long salt = 1 + (new SecureRandom().nextInt(99_999_999));
        return build(appId, appCertificate, channel, issued, LIFETIME_SECONDS, salt, LIFETIME_SECONDS);
    }

    /** Exposed with fixed time and salt so the output can be compared with Agora's reference implementation. */
    public static String build(String appId, String certificate, String channel, long issueTs, long expire, long salt, long privilegeExpire) {
        try {
            byte[] signing = hmac(uint32(issueTs), certificate.getBytes(StandardCharsets.UTF_8));
            signing = hmac(uint32(salt), signing);

            ByteArrayOutputStream info = new ByteArrayOutputStream();
            write(info, string(appId.getBytes(StandardCharsets.UTF_8)));
            write(info, uint32(issueTs));
            write(info, uint32(expire));
            write(info, uint32(salt));
            write(info, uint16(1)); // one service: RTC

            TreeMap<Integer, Long> privileges = new TreeMap<>();
            privileges.put(PRIVILEGE_JOIN, privilegeExpire);
            privileges.put(PRIVILEGE_PUBLISH_AUDIO, privilegeExpire);
            privileges.put(PRIVILEGE_PUBLISH_VIDEO, privilegeExpire);
            privileges.put(PRIVILEGE_PUBLISH_DATA, privilegeExpire);
            write(info, uint16(1)); // service type: RTC
            write(info, uint16(privileges.size()));
            for (java.util.Map.Entry<Integer, Long> entry : privileges.entrySet()) {
                write(info, uint16(entry.getKey()));
                write(info, uint32(entry.getValue()));
            }
            write(info, string(channel.getBytes(StandardCharsets.UTF_8)));
            write(info, string(new byte[0])); // uid 0 = any user

            byte[] signingInfo = info.toByteArray();
            byte[] signature = hmac(signing, signingInfo);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            write(content, string(signature));
            write(content, signingInfo);
            return "007" + Base64.getEncoder().encodeToString(deflate(content.toByteArray()));
        } catch (Exception error) {
            throw new IllegalStateException("Could not build the Agora token", error);
        }
    }

    private static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] uint16(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8)};
    }

    private static byte[] uint32(long value) {
        return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    /** A 16-bit length followed by the bytes. */
    private static byte[] string(byte[] bytes) {
        byte[] out = new byte[2 + bytes.length];
        System.arraycopy(uint16(bytes.length), 0, out, 0, 2);
        System.arraycopy(bytes, 0, out, 2, bytes.length);
        return out;
    }
}
