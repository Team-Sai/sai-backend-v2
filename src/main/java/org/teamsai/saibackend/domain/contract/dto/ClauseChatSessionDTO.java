package org.teamsai.saibackend.domain.contract.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;

/**
 * 대화형 AI 특약의 대화 상태. Redis에 JSON으로 저장한다.
 *
 * AI 서버는 상태를 갖지 않으므로, 턴마다 이 값을 같이 보낸다.
 * slots / questions / answers의 모양은 AI 서버가 정한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClauseChatSessionDTO {

    /** 대화를 시작한 사용자. 다른 사용자는 이 대화를 쓸 수 없다. */
    private Long ownerUserId;

    /** 1턴 때의 계약 정보 (AI 서버에 보내는 모양) */
    private RagClauseSuggestionRequest.ContractContext contract;

    /** 사용자가 처음 한 요청 */
    private String message;

    private List<Map<String, Object>> issues;

    private List<Map<String, Object>> slots;

    private List<Map<String, Object>> questions;

    private Map<String, String> answers;

    /** AI 서버가 "이제 작성해도 된다"(CONFIRM)고 한 뒤에만 true */
    private boolean readyToWrite;

    /** 대화를 시작할 때 발표 시연 표시가 있었는지. 미리 찾기·작성 요청에 그대로 보낸다. */
    private boolean demo;
}