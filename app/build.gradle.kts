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
            // 코드 압축(R8)은 끈다: 일부 패키지만 압축하면 Kotlin 클래스 접근 오류(IllegalAccessError)로
            // 앱이 켜지자마자 꺼졌다 (11/12 release APK 확인). 공모전 제출용이라 압축 없이 배포한다.
            optimization {
                enable = false
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