package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 서버 이해 확인 응답 (/clause-chat/understand).
 * debug 같은 내부 칸은 받지 않는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagClauseUnderstandResponse {

    private Boolean inScope;

    private List<Map<String, Object>> issues;

    private Map<String, Object> understanding;

    private List<Map<String, Object>> questions;

    private List<Map<String, Object>> fieldEdits;

    private String guide;
}