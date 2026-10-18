package com.adserve.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.adserve", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // OPS-01: the domain and the shared kernel know nothing about frameworks or infrastructure
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAnyPackage("..domain..", "com.adserve.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "javax.persistence..", "com.fasterxml..",
                    "org.apache.kafka..", "io.lettuce..", "redis..", "java.sql..", "javax.sql..");

    // OPS-02: application services depend on domain ports, never on infrastructure
    @ArchTest
    static final ArchRule applicationDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    // SRV-10 / BR-9: the serving path has no dependency on Kafka or on the analytics context
    @ArchTest
    static final ArchRule servingIsIndependentOfAnalyticsAndKafka = noClasses()
            .that().resideInAPackage("com.adserve.serving..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka..", "com.adserve.analytics..");

    // contexts reach each other only through domain ports, not through each other's infrastructure
    @ArchTest
    static final ArchRule contextsDoNotReachIntoEachOthersInfrastructure = noClasses()
            .that().resideInAnyPackage("com.adserve.campaign..", "com.adserve.budget..", "com.adserve.serving..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.adserve.tracking.infrastructure..", "com.adserve.analytics.infrastructure..");
}
