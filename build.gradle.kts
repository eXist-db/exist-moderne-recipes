plugins {
    id("org.openrewrite.build.recipe-library-base") version "2.27.4"
    id("org.openrewrite.build.recipe-repositories") version "2.27.4"
    `maven-publish`
}

// recipe-repositories only adds Code Genome when these properties are set.
require(
    providers.gradleProperty("codegenomeUsername").isPresent &&
        providers.gradleProperty("codegenomePassword").isPresent
) {
    "Set codegenomeUsername and codegenomePassword (e.g. in ~/.gradle/gradle.properties, " +
        "or ORG_GRADLE_PROJECT_codegenomeUsername / ORG_GRADLE_PROJECT_codegenomePassword) " +
        "to resolve from https://artifacts.codegenomeproject.org/maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/eXist-db/exist-moderne-recipes")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

group = "org.exist.moderne"
// Release builds take their version from the pushed `v*` tag; everything else is a snapshot.
// `mod run` caches by GAV, so for local iteration pass a unique `-PreleaseVersion=...`.
version = System.getenv("GITHUB_REF_TYPE")
    ?.takeIf { it == "tag" }
    ?.let { System.getenv("GITHUB_REF_NAME").removePrefix("v") }
    ?: findProperty("releaseVersion")?.toString()
    ?: "1.0.1-SNAPSHOT"
description = "Moderne/OpenRewrite recipes for eXist-db."

dependencies {
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.41.0"))

    // Older rewrite-core still asks for 1.0.1, which is not on Code Genome; 1.0.2 is.
    constraints {
        implementation("org.openrewrite.tools:java-object-diff:1.0.2")
    }

    implementation("org.openrewrite:rewrite-java")

    testImplementation("org.openrewrite:rewrite-test") {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.openrewrite:rewrite-java-17")
}
