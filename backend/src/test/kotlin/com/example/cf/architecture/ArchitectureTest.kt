package com.example.cf.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * DDD境界・依存方向のアーキテクチャテスト（要件定義 §6.4、詳細設計 §2.1/§2.4）。
 * ADR-0001: 単一プロジェクト構成のため、境界はArchUnitで強制する。
 */
@AnalyzeClasses(
    packages = ["com.example.cf"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureTest {

    /** ドメイン層はSpring / JPA / MyBatis / AWS SDK / HTTPへ依存しない（§17.2）。 */
    @ArchTest
    val domainMustNotDependOnFrameworks: ArchRule =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.hibernate..",
                "org.apache.ibatis..",
                "software.amazon..",
                "java.net.http..",
                "com.fasterxml.jackson..",
            )
            .because("ドメイン層はフレームワーク非依存とする（技術選定書 §17.2）")

    /** domain → application の逆流禁止（adapter → application → domain）。 */
    @ArchTest
    val domainMustNotDependOnApplication: ArchRule =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..application..")
            .because("依存方向は adapter → application → domain（基本設計 §4.3）")

    /** domain → adapter の依存禁止。 */
    @ArchTest
    val domainMustNotDependOnAdapter: ArchRule =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")

    /** application → adapter の依存禁止（Portを介する）。 */
    @ArchTest
    val applicationMustNotDependOnAdapter: ArchRule =
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("applicationはPortにのみ依存し、Adapter実装を知らない（§2.1）")

    /** 他コンテキストのadapter（内部実装）への依存禁止（§4.2: 公開契約のみ）。 */
    @ArchTest
    val noCrossContextAdapterAccess: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.project..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.review.adapter..",
                "com.example.cf.audit.adapter..",
                "com.example.cf.file.adapter..",
            )
            .because("他コンテキストへは公開契約（application/domain event）経由でアクセスする")

    /** reviewコンテキストも同様。 */
    @ArchTest
    val noCrossContextAdapterAccessFromReview: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.review..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.audit.adapter..",
                "com.example.cf.file.adapter..",
            )

    /** fileコンテキストも同様。 */
    @ArchTest
    val noCrossContextAdapterAccessFromFile: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.file..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project.adapter..",
                "com.example.cf.review.adapter..",
                "com.example.cf.audit.adapter..",
            )

    /** funding / payment コンテキストも他コンテキストのadapterへ依存しない。 */
    @ArchTest
    val noCrossContextAdapterAccessFromFunding: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.funding..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project.adapter..",
                "com.example.cf.review.adapter..",
                "com.example.cf.payment.adapter..",
                "com.example.cf.identity.adapter..",
                "com.example.cf.audit.adapter..",
                "com.example.cf.file.adapter..",
            )

    @ArchTest
    val noCrossContextAdapterAccessFromPayment: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.payment..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project.adapter..",
                "com.example.cf.funding.adapter..",
                "com.example.cf.audit.adapter..",
            )

    /** notificationコンテキストも他コンテキストのadapterへ依存しない。 */
    @ArchTest
    val noCrossContextAdapterAccessFromNotification: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.notification..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project.adapter..",
                "com.example.cf.funding.adapter..",
                "com.example.cf.payment.adapter..",
                "com.example.cf.identity.adapter..",
                "com.example.cf.audit.adapter..",
            )

    /** identityコンテキストも他コンテキストのadapterへ依存しない（工程9）。 */
    @ArchTest
    val noCrossContextAdapterAccessFromIdentity: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project.adapter..",
                "com.example.cf.review.adapter..",
                "com.example.cf.funding.adapter..",
                "com.example.cf.payment.adapter..",
                "com.example.cf.notification.adapter..",
                "com.example.cf.file.adapter..",
                "com.example.cf.audit.adapter..",
            )

    /** auditコンテキストも他コンテキストのadapterへ依存しない（工程9: 監査ログ検索）。 */
    @ArchTest
    val noCrossContextAdapterAccessFromAudit: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.audit..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project.adapter..",
                "com.example.cf.review.adapter..",
                "com.example.cf.funding.adapter..",
                "com.example.cf.payment.adapter..",
                "com.example.cf.notification.adapter..",
                "com.example.cf.file.adapter..",
                "com.example.cf.identity.adapter..",
            )

    /** shared kernelはどのコンテキストにも依存しない最小共有型に留める（§2.2）。 */
    @ArchTest
    val sharedKernelMustNotDependOnContexts: ArchRule =
        noClasses()
            .that().resideInAPackage("com.example.cf.shared.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.cf.project..",
                "com.example.cf.review..",
                "com.example.cf.identity..",
                "com.example.cf.funding..",
                "com.example.cf.payment..",
                "com.example.cf.notification..",
                "com.example.cf.file..",
                "com.example.cf.audit..",
            )
}
