// Module :core — le cœur de l'émulateur Amstrad CPC.
//
// Module Kotlin/JVM pur : AUCUNE dépendance Android. Cela permet d'exécuter
// l'intégralité des tests (Z80, mémoire, Gate Array, CRTC, FDC, DSK, boot
// firmware…) sur la JVM du poste de développement, en quelques secondes.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Le cœur est du code "chaud" : on autorise les optimisations agressives.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Les tests marqués "slow" (zexall, ~2 min) ne tournent qu'avec -PslowTests.
    useJUnitPlatform {
        if (!project.hasProperty("slowTests")) excludeTags("slow")
    }
    maxHeapSize = "1g"
    // Les tests longs (zexall, boot firmware) lisent des fichiers hors dépôt :
    // on propage les variables d'environnement qui indiquent où les trouver.
    // Répertoires de test (ROMs, exerciser Z80, disquettes de compatibilité) :
    // surchargeables par propriétés Gradle, ex. -PtestDiskDir=/chemin -PcompatOut=/chemin
    val home = System.getProperty("user.home")
    systemProperty("acpc.romDir", (project.findProperty("romDir") as String?) ?: "$home/.acpc/roms")
    systemProperty("acpc.z80TestDir", (project.findProperty("z80TestDir") as String?) ?: "$home/.acpc/z80tests")
    systemProperty("acpc.testDiskDir", (project.findProperty("testDiskDir") as String?) ?: "$home/.acpc/testdisks")
    systemProperty("acpc.tapeDir", (project.findProperty("tapeDir") as String?) ?: "$home/.acpc/tapes")
    systemProperty("acpc.cartDir", (project.findProperty("cartDir") as String?) ?: "$home/.acpc/carts")
    (project.findProperty("cartSeconds") as String?)?.let { systemProperty("acpc.cartSeconds", it) }
    systemProperty("acpc.compatOut", (project.findProperty("compatOut") as String?) ?: "$home/.acpc/compat-out")
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
