package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.support.PreparationPlanningValidator;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class PreparationPlanningValidatorTest {

    private final PreparationPlanningValidator validator =
            new PreparationPlanningValidator();

    @Test
    void acceptsImmutableAllowedDays() {
        var request = request(
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        );

        assertThatCode(() -> validator.validateRequest(request))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullElementInAllowedDays() {
        Set<DayOfWeek> days = new HashSet<>();
        days.add(DayOfWeek.MONDAY);
        days.add(null);

        assertThatThrownBy(() ->
                validator.validateRequest(request(days))
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("일정 제안 조건을 모두 입력하세요.");
    }

    private PreparationProposalRequest request(
            Set<DayOfWeek> days
    ) {
        return new PreparationProposalRequest(
                YearMonth.of(2026, 10),
                days,
                LocalTime.of(18, 0),
                LocalTime.of(21, 0),
                30,
                2,
                "",
                null
        );
    }
}