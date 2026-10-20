pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

// Adds Code Genome to pluginManagement so org.openrewrite.build.* plugin
// classpaths can resolve rewrite-* (portal alone is not enough post-cutover).
plugins {
    id("org.openrewrite.build.settings") version "2.27.1"
}

rootProject.name = "exist-moderne-recipes"
