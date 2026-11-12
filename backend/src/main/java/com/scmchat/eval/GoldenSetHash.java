package com.scmchat.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 골든셋 동결 게이트. sha256sum이 만든 해시 파일과 현재 골든셋의 SHA-256을 비교한다. */
public final class GoldenSetHash {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private GoldenSetHash() {
    }

    public static String sha256Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new GoldenSetException("cannot read " + file.toAbsolutePath().normalize(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 해시 파일의 첫 토큰(`sha256sum` 출력의 해시 부분)을 읽는다. */
    public static String readFrozenHash(Path hashFile) {
        String content;
        try {
            content = Files.readString(hashFile, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new GoldenSetException(
                    "golden set is not frozen: cannot read " + hashFile.toAbsolutePath().normalize(), e);
        }
        String hash = content.split("\\s+", 2)[0].toLowerCase();
        if (!SHA256.matcher(hash).matches()) {
            throw new GoldenSetException("not a sha256sum file: " + hashFile.toAbsolutePath().normalize());
        }
        return hash;
    }

    /** 동결된 해시와 같으면 그 해시를 돌려주고, 다르면 예외를 던진다. */
    public static String verify(Path goldenFile, Path hashFile) {
        String frozen = readFrozenHash(hashFile);
        String actual = sha256Of(goldenFile);
        if (!frozen.equals(actual)) {
            throw new GoldenSetException(
                    "golden set changed after freeze: expected " + frozen + " but was " + actual);
        }
        return actual;
    }
}
