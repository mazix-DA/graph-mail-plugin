/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

dockerCompose {
    setProjectName("graph-mail-plugin")
    isRequiredBy(project.tasks.test)

    tasks.test {
        useComposeFiles.addAll("$rootDir/docker-resources/docker-compose-base-test.yml", "docker-compose-override.yml")
    }
}

val kotlinLoggingVersion: String by project
val mockitoKotlinVersion: String by project
val valtimoVersion: String by project
val operatonVersion: String by project

configurations.testRuntimeClasspath {
    resolutionStrategy.force("org.wiremock:wiremock:3.3.1")
    exclude(group = "com.github.tomakehurst")
}

dependencies {
    compileOnly("com.ritense.valtimo:plugin-valtimo")
    compileOnly("com.ritense.valtimo:process-document")
    compileOnly("com.ritense.valtimo:contract")
    compileOnly("org.operaton.bpm:operaton-engine:$operatonVersion")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
    compileOnly("com.ritense.valtimo:temporary-resource-storage")
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    // 1.23.1 fixes CVE-2026-71497 (Cleaner XSS bypass via a malformed tag name ending in a
    // control character, for custom Safelists that permit raw-text elements). Our
    // EMAIL_HTML_SAFELIST in GraphMailPlugin.kt does not add any raw-text elements, so this
    // plugin's usage was not exploitable — pinned to the patched version regardless, since
    // this is the library the plugin's HTML sanitization relies on.
    implementation("org.jsoup:jsoup:1.23.1")

    // Testing
    testImplementation("com.ritense.valtimo:plugin-valtimo")
    testImplementation("com.ritense.valtimo:process-document")
    testImplementation("com.ritense.valtimo:building-block")
    testImplementation("com.ritense.valtimo:local-resource")
    testImplementation("com.ritense.valtimo:test-utils-common")
    testImplementation("com.ritense.valtimo:temporary-resource-storage")
    testImplementation("org.operaton.bpm:operaton-engine:$operatonVersion")
    testImplementation("org.wiremock:wiremock-standalone:3.3.1")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVersion")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

apply(from = "gradle/publishing.gradle")
