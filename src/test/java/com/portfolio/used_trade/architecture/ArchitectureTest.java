package com.portfolio.used_trade.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * ADR-1 의 경계 규칙을 테스트로 강제한다.
 *
 * <p>규칙은 도메인 이름을 나열하지 않고 패키지 패턴으로 쓴다 — 도메인이 늘어도
 * (예: assist) 이 파일은 바뀌지 않는다. 새 규칙은 ADR 에 경계 결정이 생길 때만 추가한다.
 *
 * <p>처음엔 규칙을 문서로만 관리했고, 코드가 어긋난 걸 몰랐다 (ADR-1 에러 상황 3·4).
 */
@DisplayName("아키텍처 규칙 (ADR-1)")
class ArchitectureTest {

    private static final String BASE = "com.portfolio.used_trade";
    private static final String COMMON = "common";
    private static final String ASSIST = "assist";
    /** {@code com.portfolio.used_trade.<도메인>[.하위]} 에서 도메인 이름을 뽑는다. */
    private static final Pattern DOMAIN_PACKAGE = Pattern.compile("^com\\.portfolio\\.used_trade\\.(\\w+)(\\..*)?$");

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    @DisplayName("계층 — controller → service → repository, 거꾸로 또는 건너뛰어 의존하지 않는다")
    void layers() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Controller").definedBy("..controller..")
                .layer("Service").definedBy("..service..")
                .layer("Repository").definedBy("..repository..")
                // 웹소켓 인증 (chat.config), 보안 필터 (user.security), 시드 초기화 (product.bootstrap)
                .layer("Support").definedBy("..config..", "..security..", "..bootstrap..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service", "Support")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Support")
                .check(MAIN_CLASSES);
    }

    @Test
    @DisplayName("도메인 간 — 다른 도메인의 repository 에 의존하지 않는다 (소유 Service 경유)")
    void noCrossDomainRepository() {
        classes().that(belongToADomain())
                .should(onlyUseRepositoriesOfOwnDomain())
                .check(MAIN_CLASSES);
    }

    @Test
    @DisplayName("common 은 어떤 도메인에도 의존하지 않는다")
    void commonDoesNotDependOnDomains() {
        noClasses().that().resideInAPackage(BASE + "." + COMMON + "..")
                .should().dependOnClassesThat(belongToADomain())
                .check(MAIN_CLASSES);
    }

    @Test
    @DisplayName("신규 도메인 assist 는 다른 도메인 엔티티를 직접 참조하지 않는다 (ID 값만)")
    void assistUsesIdsNotEntities() {
        noClasses().that().resideInAPackage(BASE + "." + ASSIST + "..")
                .should().dependOnClassesThat(areEntitiesOfAnotherDomainThan(ASSIST))
                .allowEmptyShould(true)   // assist 패키지가 생기기 전에도 통과
                .check(MAIN_CLASSES);
    }

    // ---------- 도메인 판별 ----------

    private static Optional<String> domainOf(JavaClass javaClass) {
        Matcher m = DOMAIN_PACKAGE.matcher(javaClass.getPackageName());
        if (!m.matches() || COMMON.equals(m.group(1))) {
            return Optional.empty();
        }
        return Optional.of(m.group(1));
    }

    private static DescribedPredicate<JavaClass> belongToADomain() {
        return DescribedPredicate.describe("belong to a domain", c -> domainOf(c).isPresent());
    }

    private static boolean isInLayer(JavaClass javaClass, String layer) {
        String pkg = javaClass.getPackageName();
        return pkg.endsWith("." + layer) || pkg.contains("." + layer + ".");
    }

    private static boolean isRepository(JavaClass javaClass) {
        return isInLayer(javaClass, "repository");
    }

    private static DescribedPredicate<JavaClass> areEntitiesOfAnotherDomainThan(String domain) {
        return DescribedPredicate.describe("are entities of another domain than " + domain,
                c -> isInLayer(c, "domain") && domainOf(c).filter(d -> !d.equals(domain)).isPresent());
    }

    private static ArchCondition<JavaClass> onlyUseRepositoriesOfOwnDomain() {
        return new ArchCondition<>("only use repositories of their own domain") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String ownDomain = domainOf(origin).orElseThrow();
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!isRepository(target)) {
                        continue;
                    }
                    domainOf(target)
                            .filter(targetDomain -> !targetDomain.equals(ownDomain))
                            .ifPresent(targetDomain -> events.add(
                                    SimpleConditionEvent.violated(dependency, dependency.getDescription())));
                }
            }
        };
    }
}
