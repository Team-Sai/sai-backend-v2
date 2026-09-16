package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementType;
import org.teamsai.saibackend.domain.settlement.type.SplitType;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementQueryService 단위 테스트")
class SettlementQueryServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long SETTLEMENT_ID = 15L;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;

    @InjectMocks
    private SettlementQueryService settlementQueryService;

    @Test
    @DisplayName("사용자의 정산 목록을 조회한다")
    void getSettlementListSuccess() {

        Long userId = 1L;

        Settlement settlement1 = settlement(
                15L,
                "정산1",
                OWNER_ID
        );

        Settlement settlement2 = settlement(
                16L,
                "정산2",
                OWNER_ID
        );

        given(
                settlementRepository.findAllAccessibleByUserId(
                        userId,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of(
                        settlement1,
                        settlement2
                )
        );

        List<SettlementListResponse> result =
                settlementQueryService
                        .getSettlementList(
                                userId
                        );

        assertThat(result)
                .hasSize(2);

        assertThat(result.get(0).settlementId())
                .isEqualTo(15L);

        assertThat(result.get(0).title())
                .isEqualTo("정산1");

        assertThat(result.get(0).role())
                .isEqualTo("OWNER");

        verify(settlementRepository)
                .findAllAccessibleByUserId(
                        userId,
                        SettlementParticipantStatus.ACTIVE
                );
    }

    @Test
    @DisplayName("조회되는 정산이 없으면 빈 목록을 반환한다")
    void getSettlementListReturnsEmptyList() {

        Long userId = 1L;

        given(
                settlementRepository.findAllAccessibleByUserId(
                        userId,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                List.of()
        );

        List<SettlementListResponse> result =
                settlementQueryService
                        .getSettlementList(
                                userId
                        );

        assertThat(result)
                .isEmpty();

        verify(settlementRepository)
                .findAllAccessibleByUserId(
                        userId,
                        SettlementParticipantStatus.ACTIVE
                );
    }

    @Test
    @DisplayName("정산 생성자는 정산 상세를 조회할 수 있다")
    void getSettlementDetailByOwner() {

        Settlement settlement =
                settlement(
                        SETTLEMENT_ID,
                        "테스트 정산",
                        OWNER_ID
                );

        given(
                settlementRepository.findDetailById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        SettlementDetailResponse result =
                settlementQueryService
                        .getSettlementDetail(
                                SETTLEMENT_ID,
                                OWNER_ID
                        );

        assertThat(result.settlementId())
                .isEqualTo(
                        SETTLEMENT_ID
                );

        assertThat(result.title())
                .isEqualTo(
                        "테스트 정산"
                );

        assertThat(result.role())
                .isEqualTo(
                        "OWNER"
                );

        verify(
                settlementParticipantRepository,
                never()
        ).existsActiveParticipant(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("정산 참여자는 정산 상세를 조회할 수 있다")
    void getSettlementDetailByMember() {

        Long memberId = 2L;

        Settlement settlement =
                settlement(
                        SETTLEMENT_ID,
                        "테스트 정산",
                        OWNER_ID
                );

        given(
                settlementRepository.findDetailById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementParticipantRepository.existsActiveParticipant(
                        SETTLEMENT_ID,
                        memberId,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                true
        );

        SettlementDetailResponse result =
                settlementQueryService
                        .getSettlementDetail(
                                SETTLEMENT_ID,
                                memberId
                        );

        assertThat(result.role())
                .isEqualTo(
                        "MEMBER"
                );
    }

    @Test
    @DisplayName(
            "정산과 관계없는 사용자는 상세를 조회할 수 없다"
    )
    void getSettlementDetailFailsWhenNotParticipant() {

        Long otherUserId = 3L;

        Settlement settlement =
                settlement(
                        SETTLEMENT_ID,
                        "테스트 정산",
                        OWNER_ID
                );

        given(
                settlementRepository.findDetailById(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                settlementParticipantRepository.existsActiveParticipant(
                        SETTLEMENT_ID,
                        otherUserId,
                        SettlementParticipantStatus.ACTIVE
                )
        ).willReturn(
                false
        );

        assertThatThrownBy(
                () ->
                        settlementQueryService
                                .getSettlementDetail(
                                        SETTLEMENT_ID,
                                        otherUserId
                                )
        ).isInstanceOf(
                DomainException.class
        ).satisfies(
                exception ->
                        assertThat(
                                ((DomainException) exception).getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode.SETTLEMENT_ACCESS_DENIED
                        )
        );
    }

    private Settlement settlement(
            Long settlementId,
            String title,
            Long ownerId
    ) {
        Settlement settlement =
                mock(Settlement.class);

        User owner =
                mock(User.class);

        lenient().when(
                settlement.getSettlementId()
        ).thenReturn(
                settlementId
        );

        lenient().when(
                settlement.getTitle()
        ).thenReturn(
                title
        );

        lenient().when(
                settlement.getOwner()
        ).thenReturn(
                owner
        );

        lenient().when(
                owner.getUserId()
        ).thenReturn(
                ownerId
        );

        lenient().when(
                owner.getName()
        ).thenReturn(
                "홍길동"
        );

        lenient().when(
                settlement.getSettlementCategory()
        ).thenReturn(
                "모임"
        );

        lenient().when(
                settlement.getSettlementType()
        ).thenReturn(
                SettlementType.SHARED
        );

        lenient().when(
                settlement.getSettlementStatus()
        ).thenReturn(
                SettlementStatus.IN_PROGRESS
        );

        lenient().when(
                settlement.getSplitType()
        ).thenReturn(
                SplitType.EQUAL
        );

        lenient().when(
                settlement.getTotalAmount()
        ).thenReturn(
                BigDecimal.valueOf(10000)
        );

        lenient().when(
                settlement.getDueDate()
        ).thenReturn(
                LocalDate.now().plusDays(7)
        );

        lenient().when(
                settlement.getCycleDate()
        ).thenReturn(
                null
        );

        lenient().when(
                settlement.getRecurringSettlement()
        ).thenReturn(
                null
        );

        lenient().when(
                settlement.getCreatedAt()
        ).thenReturn(
                LocalDateTime.now()
        );

        return settlement;
    }
}