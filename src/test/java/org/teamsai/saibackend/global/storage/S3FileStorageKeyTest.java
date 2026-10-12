package org.teamsai.saibackend.global.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class S3FileStorageKeyTest {

    @Test
    void prefix가_없으면_빈_값() {
        assertThat(S3FileStorage.normalizePrefix(null)).isEmpty();
        assertThat(S3FileStorage.normalizePrefix("")).isEmpty();
        assertThat(S3FileStorage.normalizePrefix("  ")).isEmpty();
    }

    @Test
    void prefix_끝에_슬래시를_붙인다() {
        assertThat(S3FileStorage.normalizePrefix("files")).isEqualTo("files/");
        assertThat(S3FileStorage.normalizePrefix("files/")).isEqualTo("files/");
    }
}