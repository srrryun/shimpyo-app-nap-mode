plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.hyundaitest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.hyundaitest"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.pleos.vehicle)

    // 쉼표 1단계: 순서 있는 작업(타이머·알람·차량 제어)용 코루틴, 화면 수명주기 연동
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
}