package com.scmchat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenSetHashTest {

    // printf 'abc' | sha256sum
    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path dir;

    @Test
    void computesSha256OfFileBytes() throws IOException {
        assertThat(GoldenSetHash.sha256Of(golden("abc"))).isEqualTo(SHA256_OF_ABC);
    }

    @Test
    void acceptsSha256sumFileFormat() throws IOException {
        Path hashFile = Files.writeString(dir.resolve("golden_set.sha256"),
                SHA256_OF_ABC + "  eval/golden_set.json\n");

        assertThat(GoldenSetHash.verify(golden("abc"), hashFile)).isEqualTo(SHA256_OF_ABC);
    }

    @Test
    void rejectsGoldenSetChangedAfterFreeze() throws IOException {
        Path hashFile = Files.writeString(dir.resolve("golden_set.sha256"), SHA256_OF_ABC + "  golden_set.json\n");

        assertThatThrownBy(() -> GoldenSetHash.verify(golden("abcd"), hashFile))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("changed after freeze");
    }

    @Test
    void rejectsMissingHashFileAsNotFrozen() throws IOException {
        Path golden = golden("abc");

        assertThatThrownBy(() -> GoldenSetHash.verify(golden, dir.resolve("golden_set.sha256")))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("not frozen");
    }

    @Test
    void rejectsHashFileWithoutSha256() throws IOException {
        Path hashFile = Files.writeString(dir.resolve("golden_set.sha256"), "not-a-hash  golden_set.json\n");

        assertThatThrownBy(() -> GoldenSetHash.readFrozenHash(hashFile))
                .isInstanceOf(GoldenSetException.class)
                .hasMessageContaining("not a sha256sum file");
    }

    private Path golden(String content) throws IOException {
        return Files.writeString(dir.resolve("golden_set.json"), content);
    }
}
