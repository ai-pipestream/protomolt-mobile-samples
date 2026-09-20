import com.google.protobuf.gradle.*

plugins {
    id("com.android.application")
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The engine is consumed as built artifacts from a pinned checkout next to this
// repository: the AAR from scripts/build-engine-android.sh, and its protobuf
// contracts, which Gradle compiles here (no generated code is checked in).
val sample = rootProject.projectDir.resolve("..").canonicalFile
val engine = providers.gradleProperty("engineDir").map { file(it) }
    .getOrElse(sample.resolve("../protomolt-search")).canonicalFile

android {
    namespace = "ai.pipestream.samples.courtsearch"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.pipestream.samples.courtsearch"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        proto {
            srcDir(engine.resolve("proto"))
            srcDir(sample.resolve("proto"))
            include("ai/protomolt/search/v1/*.proto")
            include("ai/protomolt/search/mobile/v1/mobile.proto")
            include("court/v1/court.proto")
        }
        // court.desc and the 25-opinion fixture, shared with iOS and the probe.
        assets.srcDir(sample.resolve("fixtures"))
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    // Android projects get no default output; without this protoc is invoked with
    // nothing to generate and fails with "Missing output directives".
    //
    // Lite, not full: the engine's contracts have fields named `descriptor`
    // (GetVectorBackendResponse, SchemaEnum, SchemaField, the WAL's SourceReference),
    // and full protobuf-java generates a getDescriptor() for them that collides with
    // the runtime's own static getDescriptor(). Lite has no descriptors, so it
    // compiles, and it is the smaller runtime Android wants anyway.
    generateProtoTasks {
        all().forEach { task -> task.builtins { create("java") { option("lite") } } }
    }
}

dependencies {
    implementation(files(sample.resolve("android/libs/ProtomoltSearch.aar")))
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
