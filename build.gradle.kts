plugins {
    `java-library`
    id("org.gradlex.java-ecosystem-capabilities") version "1.5.2.00"
    id("dev.jacomet.logging-capabilities") version "0.11.1"
    id("io.fuchs.gradle.classpath-collision-detector") version "0.3"
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/repositories/releases")
    maven("https://maven.scijava.org/content/groups/public")
}

dependencies {
    implementation(platform(platforms.scijava))
    runtimeOnly("org.jogamp.gluegen:gluegen-rt:2.5.0-natives-linux-amd64")
    //    implementation(libs.org.jogamp.gluegen.gluegenRt)
    runtimeOnly("org.jogamp.jogl:jogl-all:2.5.0-natives-linux-amd64")
    //    implementation(libs.org.jogamp.jogl.joglAll)
    implementation(libs.fiji.get3dViewer())
    implementation(libs.fiji.analyzeskeleton)
    implementation(libs.fiji.skeletonize3d)
    implementation(libs.fiji.vibLib)
    implementation(libs.fiji.lib)
    implementation(libs.fiji.palOptimization)
    implementation(libs.scifio.scifio)
    implementation(libs.imagej.imagej)
    implementation(libs.imagej.common)
    implementation(libs.imagej.legacy)
    implementation(libs.imagej.mesh)
    implementation(libs.imagej.ops)
    implementation(libs.imagej.updater)
    implementation(libs.imagej.ij)
    implementation(libs.imglib2.algorithm)
    implementation(libs.imglib2.cache)
    implementation(libs.imglib2.ij)
    implementation(libs.imglib2.roi)
    implementation(libs.imglib2.imglib2)
    implementation(libs.scijava.batchProcessor)
    implementation(libs.scijava.j3dcore)
    implementation(libs.scijava.common)
    implementation(libs.scijava.plot)
    implementation(libs.scijava.uiAwt)
    implementation(libs.scijava.scriptEditor)
    implementation(libs.scijava.vecmath)
    implementation(libs.scijava.uiSwing)
    implementation(libs.com.fifesoft.rsyntaxtextarea)
    implementation(libs.com.formdev.flatlaf)
    implementation(libs.com.github.vlsi.mxgraph.jgraphx)
    implementation(libs.commons.io.commonsIo)
    implementation(libs.org.apache.commons.commonsLang3)
    implementation(libs.org.apache.commons.commonsMath3)
    implementation(libs.org.apache.commons.commonsText)
    // no longer needed!? org.apache.xmlgraphics:batik-svggen <scope>runtime
    // kotlin
    implementation(libs.org.jfree.jfreechart)
    implementation(libs.org.jgrapht.jgraphtCore)
    implementation(libs.org.jgrapht.jgraphtExt)
    implementation(libs.org.jogamp.gluegen.gluegenRt)
    implementation(libs.org.jogamp.jogl.joglAll)
    implementation(libs.org.joml.joml)
    implementation(libs.org.json.json)
    implementation(libs.org.jzy3d.jzy3dCore)
    implementation(libs.org.jzy3d.jzy3dCoreAwt)
    implementation(libs.org.jzy3d.jzy3dEmulGlAwt)
    implementation(libs.org.jzy3d.jzy3dNativeJoglAwt)
    implementation(libs.org.jzy3d.jzy3dNativeJoglCore)
    implementation(libs.org.jzy3d.jzy3dNativeJoglSwing)
    implementation(libs.org.jzy3d.jzy3dTester)
    implementation("com.formdev:jide-oss:3.7.14")
    implementation("com.github.haifengl:smile-base:3.0.3")
    implementation("com.github.haifengl:smile-core:3.0.3")
    implementation(libs.com.squareup.okhttp3.okhttp)
    implementation("it.unimi.dsi:fastutil-core:8.5.13")
    implementation("graphics.scenery:scenery:0.10.0") {
        exclude("org.biojava", "biojava-core")
        exclude("org.biojava", "biojava-structure")
        exclude("org.biojava", "biojava-modfinder")
        exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    }
    // org.jetbrains:annotations
    implementation(libs.org.jheaps.jheaps)
    implementation("sc.iview:sciview:0.2.0") {
        exclude("org.jogamp.gluegen", "gluegen-rt")
        exclude("org.jogamp.jogl", "jogl-all")
        exclude("org.apache.logging.log4j", "log4j-1.2-api")
        exclude("org.biojava", "biojava-core")
        exclude("org.biojava", "biojava-structure")
        exclude("org.biojava", "biojava-modfinder")
        exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    }

    // runtime com.formdev:flatlaf-jide-oss optional
    runtimeOnly(libs.org.jzy3d.jzy3dJglAwt)
    runtimeOnly("org.webjars:font-awesome:6.5.1")

    implementation(libs.fiji.trainableSegmentation)
    implementation(libs.fiji.labkitUi)
}

loggingCapabilities { enforceLog4J2() }

publishing {
    repositories {
        maven {
            name = "sciJava"
            credentials(PasswordCredentials::class)
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
    publications {
        register<MavenPublication>("maven") {
            groupId = "org.morphonets"
            artifactId = "SNT"
            version = "4.3.0-pre-release3"

            from(components["java"])

            pom {
                withXml {
                    fun StringBuilder.replace(old: String, new: String) {
                        val start = indexOf(old)
                        replace(start, start + old.length, new)
                    }
                    val builder = asString().clear().append(file("pom.xml").readText())
                    builder.replace("\t\t<version>37.0.0</version>", "\t\t<version>38.0.0-SNAPSHOT</version>")
                    builder.replace("\t<version>4.2.2-SNAPSHOT</version>", "\t<version>$version</version>")
                }
            }
        }
    }
}

tasks.test { jvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED") }