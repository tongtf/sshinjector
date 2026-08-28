plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

buildscript {
    configurations.all {
        resolutionStrategy {
            force("org.bitbucket.b_c:jose4j:0.9.6")
            force("org.jdom:jdom2:2.0.6.1")
            force("com.google.protobuf:protobuf-java:4.35.1")
            force("commons-io:commons-io:2.17.0")
            force("org.apache.commons:commons-compress:1.27.1")
            force("org.apache.commons:commons-lang3:3.18.0")
            force("org.bouncycastle:bcprov-jdk18on:1.85.2")
            force("org.bouncycastle:bcpkix-jdk18on:1.85")
            force("org.bouncycastle:bcutil-jdk18on:1.85")
            force("org.apache.httpcomponents:httpmime:4.5.14")
        }
    }
}

subprojects {
    configurations.all {
        resolutionStrategy {
            force("org.bouncycastle:bcprov-jdk18on:1.85.2")
            force("org.bouncycastle:bcpkix-jdk18on:1.85")
            force("org.bouncycastle:bcutil-jdk18on:1.85")
            force("com.google.protobuf:protobuf-java:4.35.1")
            force("org.apache.commons:commons-compress:1.27.1")
            force("org.apache.commons:commons-lang3:3.18.0")
            force("commons-io:commons-io:2.17.0")
            force("org.bitbucket.b_c:jose4j:0.9.6")
            force("org.jdom:jdom2:2.0.6.1")
            force("org.apache.httpcomponents:httpclient:4.5.14")
            force("org.apache.httpcomponents:httpcore:4.4.16")
            force("org.apache.httpcomponents:httpmime:4.5.14")
            force("com.google.protobuf:protobuf-java-util:4.35.1")
        }
    }
}
