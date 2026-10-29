package com.intel.cosbench.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void fingerprintChangesWhenCommandOrPayloadChanges() {
        String first = RequestFingerprint.sha256("submit", "one".getBytes());
        assertThat(first).hasSize(64);
        assertThat(RequestFingerprint.sha256("submit", "one".getBytes())).isEqualTo(first);
        assertThat(RequestFingerprint.sha256("submit", "two".getBytes())).isNotEqualTo(first);
        assertThat(RequestFingerprint.sha256("launch", "one".getBytes())).isNotEqualTo(first);
    }
}
