package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 서버 근거 법령 미리 찾기 응답 (/clause-chat/prepare).
 * 법령 모양은 AI 서버가 정하므로 Map 그대로 보관했다가 write에 그대로 보낸다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagClausePrepareResponse {

    private List<Map<String, Object>> references;
}