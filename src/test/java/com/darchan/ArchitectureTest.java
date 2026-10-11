package com.darchan;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering rules for production classes only (tests may cross layers, e.g. the gate-level ALU test uses the
 * behavioral golden model). {@code com.darchan.cosim} is the one production package that may use both layers; no
 * layer may depend on it.
 */
@AnalyzeClasses(packages = "com.darchan", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule physicalDoesNotDependOnBehavioral = noClasses()
            .that().resideInAPackage("com.darchan.physical..")
            .should().dependOnClassesThat().resideInAPackage("com.darchan.behavioral..");

    @ArchTest
    static final ArchRule behavioralDoesNotDependOnPhysical = noClasses()
            .that().resideInAPackage("com.darchan.behavioral..")
            .should().dependOnClassesThat().resideInAPackage("com.darchan.physical..");

    @ArchTest
    static final ArchRule diagDoesNotDependOnPhysical = noClasses()
            .that().resideInAPackage("com.darchan.diag..")
            .should().dependOnClassesThat().resideInAPackage("com.darchan.physical..");

    @ArchTest
    static final ArchRule diagDoesNotDependOnBehavioral = noClasses()
            .that().resideInAPackage("com.darchan.diag..")
            .should().dependOnClassesThat().resideInAPackage("com.darchan.behavioral..");

    @ArchTest
    static final ArchRule layersDoNotDependOnCosim = noClasses()
            .that().resideInAnyPackage("com.darchan.physical..", "com.darchan.behavioral..", "com.darchan.diag..")
            .should().dependOnClassesThat().resideInAPackage("com.darchan.cosim..");
}
