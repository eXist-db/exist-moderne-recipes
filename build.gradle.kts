plugins {
    id("org.openrewrite.build.recipe-library-base") version "2.20.0"
    id("org.openrewrite.build.recipe-repositories") version "2.20.0"
    `maven-publish`
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
version = "1.0.0"
description = "Moderne/OpenRewrite recipes for eXist-db."

dependencies {
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.37.0"))

    implementation("org.openrewrite:rewrite-java")

    testImplementation("org.openrewrite:rewrite-test") {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }
    testImplementation("org.assertj:assertj-core:latest.release")
    testRuntimeOnly("org.openrewrite:rewrite-java-17")
}
