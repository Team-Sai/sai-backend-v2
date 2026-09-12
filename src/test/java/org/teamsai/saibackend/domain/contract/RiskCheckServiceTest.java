package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.service.RiskCheckService;
import org.teamsai.saibackend.domain.contract.type.ContractRelationType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RiskCheckService 단위 테스트")
class RiskCheckServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private LoanContractRepository loanContractRepository;

    @InjectMocks
    private RiskCheckService riskCheckService;

    @Test
    @DisplayName("리포지토리가 반환한 이전 차용금 합계를 그대로 반환한다")
    void sumCompletedPrincipalByCreditorReturnsSumFromRepository() {
        given(loanContractRepository.sumPrincipalByCreditorAndStatusAndRelationType(
                USER_ID, ContractStatus.COMPLETED, ContractRelationType.FAMILY
        )).willReturn(30_000_000L);

        Long result = riskCheckService.sumCompletedPrincipalByCreditor(USER_ID);

        assertThat(result).isEqualTo(30_000_000L);
        verify(loanContractRepository).sumPrincipalByCreditorAndStatusAndRelationType(
                USER_ID, ContractStatus.COMPLETED, ContractRelationType.FAMILY
        );
    }

    @Test
    @DisplayName("이전에 완료된 가족 간 계약이 없으면 0을 반환한다")
    void sumCompletedPrincipalByCreditorReturnsZeroWhenNoCompletedFamilyContracts() {
        given(loanContractRepository.sumPrincipalByCreditorAndStatusAndRelationType(
                USER_ID, ContractStatus.COMPLETED, ContractRelationType.FAMILY
        )).willReturn(0L);

        Long result = riskCheckService.sumCompletedPrincipalByCreditor(USER_ID);

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("조회 대상 사용자 ID, COMPLETED 상태, FAMILY 관계를 그대로 리포지토리에 전달한다")
    void sumCompletedPrincipalByCreditorPassesFixedCriteriaToRepository() {
        Long otherUserId = 42L;
        given(loanContractRepository.sumPrincipalByCreditorAndStatusAndRelationType(
                otherUserId, ContractStatus.COMPLETED, ContractRelationType.FAMILY
        )).willReturn(5_000_000L);

        riskCheckService.sumCompletedPrincipalByCreditor(otherUserId);

        verify(loanContractRepository).sumPrincipalByCreditorAndStatusAndRelationType(
                otherUserId, ContractStatus.COMPLETED, ContractRelationType.FAMILY
        );
    }
}
