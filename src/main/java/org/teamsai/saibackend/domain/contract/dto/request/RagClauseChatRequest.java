package org.teamsai.saibackend.domain.contract.dto.request;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 서버 대화형 특약 요청 (/clause-chat/route, /clause-chat/write/stream).
 *
 * 값 칸(slots)·질문(questions)의 모양은 AI 서버가 정하므로
 * 백엔드는 Map 그대로 보관했다가 그대로 보낸다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagClauseChatRequest {

    private RagClauseSuggestionRequest.ContractContext contract;

    private String message;

    private List<Map<String, Object>> issues;

    private List<Map<String, Object>> slots;

    private List<Map<String, Object>> questions;

    private Map<String, String> answers;

    /** /prepare로 미리 찾은 근거 법령 (write에서만). 없으면 null → AI가 지금처럼 검색한다. */
    private List<Map<String, Object>> references;

    /** 발표 시연 표시 (미리 찾기·작성). 기본 false */
    private boolean demo;
}