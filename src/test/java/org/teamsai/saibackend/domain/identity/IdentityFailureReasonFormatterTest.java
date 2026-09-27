package org.teamsai.saibackend.domain.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.identity.dto.response.PortOneIdentityResponse;
import org.teamsai.saibackend.domain.identity.support.IdentityFailureReasonFormatter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdentityFailureReasonFormatter 단위 테스트")
class IdentityFailureReasonFormatterTest {

    private final IdentityFailureReasonFormatter formatter =
            new IdentityFailureReasonFormatter();

    @Test
    @DisplayName("실패 사유와 PG 코드, PG 메시지를 구분자로 연결한다")
    void createFailureReasonCombinesFailureValues() {
        PortOneIdentityResponse response =
                new PortOneIdentityResponse(
                        "identity-verification-test",
                        "FAILED",
                        null,
                        new PortOneIdentityResponse.Failure(
                                "인증 실패",
                                "PG-001",
                                "사용자 인증 실패"
                        )
                );

        String result =
                formatter.createFailureReason(response);

        assertThat(result)
                .isEqualTo(
                        "인증 실패 | PG-001 | 사용자 인증 실패"
                );
    }

    @Test
    @DisplayName("비어 있는 실패 정보는 제외하고 조합한다")
    void createFailureReasonIgnoresBlankValues() {
        PortOneIdentityResponse response =
                new PortOneIdentityResponse(
                        "identity-verification-test",
                        "FAILED",
                        null,
                        new PortOneIdentityResponse.Failure(
                                "인증 실패",
                                null,
                                " "
                        )
                );

        String result =
                formatter.createFailureReason(response);

        assertThat(result)
                .isEqualTo("인증 실패");
    }

    @Test
    @DisplayName("실패 정보가 없으면 FAILED를 반환한다")
    void createFailureReasonReturnsDefaultWhenFailureIsNull() {
        PortOneIdentityResponse response =
                new PortOneIdentityResponse(
                        "identity-verification-test",
                        "FAILED",
                        null,
                        null
                );

        String result =
                formatter.createFailureReason(response);

        assertThat(result)
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("실패 정보가 모두 비어 있으면 FAILED를 반환한다")
    void createFailureReasonReturnsDefaultWhenFailureValuesAreEmpty() {
        PortOneIdentityResponse response =
                new PortOneIdentityResponse(
                        "identity-verification-test",
                        "FAILED",
                        null,
                        new PortOneIdentityResponse.Failure(
                                null,
                                "",
                                " "
                        )
                );

        String result =
                formatter.createFailureReason(response);

        assertThat(result)
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("실패 정보의 앞뒤 공백을 제거하고 조합한다")
    void createFailureReasonTrimsValues() {
        PortOneIdentityResponse response =
                new PortOneIdentityResponse(
                        "identity-verification-test",
                        "FAILED",
                        null,
                        new PortOneIdentityResponse.Failure(
                                " 인증 실패 ",
                                " PG-001 ",
                                " 사용자 인증 실패 "
                        )
                );

        String result =
                formatter.createFailureReason(response);

        assertThat(result)
                .isEqualTo(
                        "인증 실패 | PG-001 | 사용자 인증 실패"
                );
    }

    @Test
    @DisplayName("실패 사유가 255자를 초과하면 255자로 자른다")
    void truncateFailureReasonLimitsLengthTo255() {
        String result =
                formatter.truncateFailureReason(
                        "x".repeat(300)
                );

        assertThat(result)
                .hasSize(255);

        assertThat(result)
                .isEqualTo(
                        "x".repeat(255)
                );
    }

    @Test
    @DisplayName("실패 사유가 255자 이하면 그대로 반환한다")
    void truncateFailureReasonKeepsValidLength() {
        String failureReason =
                "인증 실패 | PG-001 | 사용자 인증 실패";

        String result =
                formatter.truncateFailureReason(
                        failureReason
                );

        assertThat(result)
                .isEqualTo(failureReason);
    }

    @Test
    @DisplayName("실패 사유의 앞뒤 공백을 제거한다")
    void truncateFailureReasonTrimsValue() {
        String result =
                formatter.truncateFailureReason(
                        "  인증 실패  "
                );

        assertThat(result)
                .isEqualTo("인증 실패");
    }

    @Test
    @DisplayName("실패 사유가 비어 있으면 FAILED를 반환한다")
    void truncateFailureReasonReturnsDefaultWhenBlank() {
        String result =
                formatter.truncateFailureReason(" ");

        assertThat(result)
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("실패 사유가 null이면 FAILED를 반환한다")
    void truncateFailureReasonReturnsDefaultWhenNull() {
        String result =
                formatter.truncateFailureReason(null);

        assertThat(result)
                .isEqualTo("FAILED");
    }
}