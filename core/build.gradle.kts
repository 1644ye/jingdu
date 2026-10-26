plugins { kotlin("jvm") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { implementation("com.google.code.gson:gson:2.11.0"); testImplementation("junit:junit:4.13.2") }
tasks.test { testLogging { events("passed", "skipped", "failed") } }
