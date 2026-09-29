package com.aadil.shopqueue;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the package rules from CLAUDE.md so they can't silently erode. */
@AnalyzeClasses(packages = "com.aadil.shopqueue", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule workerDoesNotDependOnJob = noClasses()
            .that().resideInAPackage("..worker..")
            .should().dependOnClassesThat().resideInAPackage("..job..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orderDoesNotDependOnJob = noClasses()
            .that().resideInAPackage("..order..")
            .should().dependOnClassesThat().resideInAPackage("..job..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule jobDoesNotDependOnOrder = noClasses()
            .that().resideInAPackage("..job..")
            .should().dependOnClassesThat().resideInAPackage("..order..")
            .allowEmptyShould(true);
}
