package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 닉네임 변경(PATCH /api/users/me/nickname) API를 실제 JWT 인증·Controller·Service·DB 흐름으로 검증.
// 본인 닉네임만 바뀌고 다른 필드·다른 사용자는 유지되는지, 규칙(2~16자)이 회원가입과 같은지,
// 변경이 닉네임을 표시하는 화면(모집 목록)에 바로 반영되는지도 함께 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserNicknameIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private EntityManager entityManager;

    private User createUser(String nickname) {
        return userRepository.saveAndFlush(new User(
                "nickname-" + UUID.randomUUID() + "@example.com",
                "encoded-password",
                nickname,
                DefaultTalkLevel.LIGHT_CHAT,
                Instant.now()
        ));
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    private ResultActions changeNickname(String token, String nickname) throws Exception {
        return mockMvc.perform(patch("/api/users/me/nickname")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\": \"" + nickname + "\"}"));
    }

    private String storedNickname(Long userId) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(userId).orElseThrow().getNickname();
    }

    @Test
    @DisplayName("닉네임을 변경하면 저장되고 내 정보 조회에도 바로 반영되며 다른 필드는 그대로다")
    void 닉네임_변경() throws Exception {
        User user = createUser("기존닉네임");
        String token = bearerToken(user.getId());

        changeNickname(token, "새닉네임")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"));

        assertThat(storedNickname(user.getId())).isEqualTo("새닉네임");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"))
                // 닉네임 외 다른 필드는 변경되지 않는다
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.defaultTalkLevel").value("LIGHT_CHAT"));
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("다른 사용자의 닉네임은 변경되지 않는다")
    void 다른_사용자의_닉네임은_유지된다() throws Exception {
        User me = createUser("내닉네임");
        User other = createUser("남의닉네임");

        changeNickname(bearerToken(me.getId()), "바뀐닉네임").andExpect(status().isOk());

        assertThat(storedNickname(me.getId())).isEqualTo("바뀐닉네임");
        assertThat(userRepository.findById(other.getId()).orElseThrow().getNickname()).isEqualTo("남의닉네임");
    }

    @Test
    @DisplayName("회원가입과 같은 규칙: 2자·16자는 허용한다")
    void 경계_길이는_허용한다() throws Exception {
        User user = createUser("기존닉네임");
        String token = bearerToken(user.getId());

        changeNickname(token, "가나").andExpect(status().isOk());
        assertThat(storedNickname(user.getId())).isEqualTo("가나");

        String sixteen = "가".repeat(16);
        changeNickname(token, sixteen).andExpect(status().isOk());
        assertThat(storedNickname(user.getId())).isEqualTo(sixteen);
    }

    @Test
    @DisplayName("1자 또는 17자 닉네임은 한글 메시지와 함께 400으로 거부하고 기존 닉네임을 유지한다")
    void 길이_규칙을_어기면_400을_반환한다() throws Exception {
        User user = createUser("기존닉네임");
        String token = bearerToken(user.getId());

        changeNickname(token, "가")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임은 2자 이상 16자 이하로 입력해주세요."));
        changeNickname(token, "가".repeat(17))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임은 2자 이상 16자 이하로 입력해주세요."));

        assertThat(storedNickname(user.getId())).isEqualTo("기존닉네임");
    }

    // NUL 문자(0x00)는 PostgreSQL이 저장하지 못해 DB 단계에서 500이 났다. 값은 JSON 이스케이프 문자열로 넘긴다
    @Test
    @DisplayName("NUL 문자가 있는 닉네임은 400으로 거부하고 기존 닉네임을 유지한다")
    void NUL_문자가_있으면_400을_반환한다() throws Exception {
        User user = createUser("기존닉네임");

        changeNickname(bearerToken(user.getId()), "a\\u0000b")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임에 사용할 수 없는 문자가 포함되어 있어요."));

        assertThat(storedNickname(user.getId())).isEqualTo("기존닉네임");
    }

    @Test
    @DisplayName("공백뿐이거나 비어 있거나 누락된 닉네임은 400으로 거부하고 기존 닉네임을 유지한다")
    void 공백_빈값_누락은_400을_반환한다() throws Exception {
        User user = createUser("기존닉네임");
        String token = bearerToken(user.getId());

        // 3칸 공백: 길이 규칙은 통과하지만 공백뿐이라 NotBlank에 걸린다
        changeNickname(token, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임을 입력해주세요."));
        changeNickname(token, "").andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/users/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임을 입력해주세요."));
        mockMvc.perform(patch("/api/users/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": null}"))
                .andExpect(status().isBadRequest());

        assertThat(storedNickname(user.getId())).isEqualTo("기존닉네임");
    }

    // "가입과 같은 규칙"이 길이만이 아니라 사용자가 보는 메시지까지 성립하는지 두 API의 응답을 직접 비교한다.
    // 가입과 변경이 서로 다른 DTO라 한쪽만 고치면 다시 어긋날 수 있는데, 이 테스트가 그것을 잡는다.
    @Test
    @DisplayName("닉네임 검증 실패 메시지가 회원가입과 같다")
    void 검증_메시지가_회원가입과_같다() throws Exception {
        User user = createUser("기존닉네임");

        for (String nickname : new String[]{"가", "가".repeat(17), "   "}) {
            String signUpMessage = mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "runner@example.com",
                                      "password": "test1234",
                                      "nickname": "%s",
                                      "defaultTalkLevel": "SILENT"
                                    }
                                    """.formatted(nickname)))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String changeMessage = changeNickname(bearerToken(user.getId()), nickname)
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(changeMessage).isEqualTo(signUpMessage);
        }
    }

    @Test
    @DisplayName("다른 사용자와 같은 닉네임도 회원가입과 동일하게 허용한다")
    void 중복_닉네임을_허용한다() throws Exception {
        createUser("겹치는닉네임");
        User me = createUser("내닉네임");

        changeNickname(bearerToken(me.getId()), "겹치는닉네임").andExpect(status().isOk());

        assertThat(storedNickname(me.getId())).isEqualTo("겹치는닉네임");
    }

    @Test
    @DisplayName("인증 없이 요청하면 401이 반환된다")
    void 인증_없는_변경_거부() throws Exception {
        mockMvc.perform(patch("/api/users/me/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"새닉네임\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴한 계정의 토큰으로는 닉네임을 변경할 수 없다(401)")
    void 탈퇴한_계정은_변경할_수_없다() throws Exception {
        User user = createUser("기존닉네임");
        String token = bearerToken(user.getId());
        user.withdraw();
        userRepository.saveAndFlush(user);

        changeNickname(token, "새닉네임").andExpect(status().isUnauthorized());
    }

    // 완료 기준: 닉네임이 표시되는 곳에 변경이 바로 반영된다. 닉네임은 users.nickname을 직접 조회하므로
    // 별도 동기화가 필요 없다는 전제를 실제 모집 목록 응답으로 확인한다.
    @Test
    @DisplayName("변경한 닉네임이 모집 탭 목록의 작성자 닉네임에 바로 반영된다")
    void 모집_목록의_작성자_닉네임에_반영된다() throws Exception {
        User host = createUser("옛날닉네임");
        User viewer = createUser("조회자닉네임");
        MatchRequest post = matchRequestRepository.save(new MatchRequest(
                host.getId(), OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        ));
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                post.getId(), courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );
        String boardPath = "$.items[?(@.id == " + post.getId() + ")].author.nickname";

        mockMvc.perform(get("/api/matching/board").header(HttpHeaders.AUTHORIZATION, bearerToken(viewer.getId())))
                .andExpect(jsonPath(boardPath).value(hasItem("옛날닉네임")));

        changeNickname(bearerToken(host.getId()), "새로운닉네임").andExpect(status().isOk());
        entityManager.flush(); // 네이티브 쿼리(searchBoard) 전에 변경을 DB에 반영

        mockMvc.perform(get("/api/matching/board").header(HttpHeaders.AUTHORIZATION, bearerToken(viewer.getId())))
                .andExpect(jsonPath(boardPath).value(hasItem("새로운닉네임")));
    }
}
