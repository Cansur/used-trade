package com.portfolio.used_trade.product.bootstrap;

import com.portfolio.used_trade.product.domain.Category;
import com.portfolio.used_trade.product.repository.CategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link CategoryDataInitializer} 단위 테스트.
 *
 * <p>의도: 시드 로직이 idempotent 한지, 그리고 빈 DB 에서 정확히 SEED 크기만큼만
 * 저장되는지 검증. 실제 DB 는 사용하지 않는다 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryDataInitializer 단위 테스트")
class CategoryDataInitializerTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryDataInitializer initializer;

    @Test
    @DisplayName("빈 DB — 모든 시드 카테고리가 저장되고 이름/순서가 정확히 매핑된다")
    void run_emptyDb_savesAllSeeds() {
        // ── Arrange ──
        // 어떤 이름으로 묻든 "없음" 응답 → 전부 신규 INSERT 경로
        given(categoryRepository.existsByName(anyString())).willReturn(false);

        // ── Act ──
        initializer.run();

        // ── Assert ──
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository, times(10)).save(captor.capture());

        List<Category> saved = captor.getAllValues();

        // 시드 마스터의 첫/끝 이름과 displayOrder 검증 — 회귀 방지
        assertThat(saved.get(0).getName()).isEqualTo("전자기기");
        assertThat(saved.get(0).getDisplayOrder()).isEqualTo(1);
        assertThat(saved.get(0).isActive()).isTrue();

        assertThat(saved.get(9).getName()).isEqualTo("기타");
        assertThat(saved.get(9).getDisplayOrder()).isEqualTo(10);

        // displayOrder 가 1..10 까지 단조 증가 — 순서 깨짐 회귀 방지
        for (int i = 0; i < saved.size(); i++) {
            assertThat(saved.get(i).getDisplayOrder()).isEqualTo(i + 1);
            assertThat(saved.get(i).isActive()).isTrue();
        }
    }

    @Test
    @DisplayName("이미 모든 시드가 존재 — save 호출 0회 (idempotent)")
    void run_allSeedsExist_savesNothing() {
        // ── Arrange ──
        given(categoryRepository.existsByName(anyString())).willReturn(true);

        // ── Act ──
        initializer.run();

        // ── Assert ──
        // 재기동 시마다 INSERT 가 발생하면 운영자가 바꾼 데이터를 덮어쓸 위험.
        // 이 테스트가 빨갛게 켜지면 시드 로직이 idempotency 를 잃은 것.
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    @DisplayName("일부만 존재 — 누락된 시드만 INSERT")
    void run_partialSeedsExist_savesOnlyMissing() {
        // ── Arrange ──
        // "전자기기" 만 이미 있고 나머지 9개는 없음
        given(categoryRepository.existsByName(anyString())).willReturn(false);
        given(categoryRepository.existsByName("전자기기")).willReturn(true);

        // ── Act ──
        initializer.run();

        // ── Assert ──
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository, times(9)).save(captor.capture());

        // 저장된 9개 중 "전자기기" 가 포함되지 않아야 함
        assertThat(captor.getAllValues())
                .extracting(Category::getName)
                .doesNotContain("전자기기")
                .contains("의류/패션", "기타");
    }
}
