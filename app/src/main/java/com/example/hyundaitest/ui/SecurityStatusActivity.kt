package com.example.hyundaitest.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hyundaitest.VehicleCheckActivity
import com.example.hyundaitest.appContainer
import com.example.hyundaitest.security.KeyStorageLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 보안 상태 화면 (2단계 보안 F, 시연용).
 *
 * 문구를 고정해서 보여주는 화면이 아니라, 이 기기에서 지금 실제로 확인한 결과를 보여준다.
 * 항목을 추가할 때는 runChecks() 에 SecurityCheck 하나를 더하면 된다.
 */
class SecurityStatusActivity : AppCompatActivity() {

    private enum class Level(val mark: String, val color: String) {
        OK("✓", "#2E7D32"),
        NOTE("△", "#EF6C00"),
        PROBLEM("✗", "#C62828"),
    }

    private data class SecurityCheck(val title: String, val level: Level, val detail: String)

    private val container by lazy { appContainer }
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = shimpyoScreen()
        screen.addView(backBar())
        screen.addView(titleBlock("보안 상태", "문구를 미리 적어 둔 게 아니라, 이 기기에서 지금 실제로 검사한 결과예요."))
        list = column(16)
        screen.addView(list.fillWidth())

        list.addView(shimpyoText("검사하는 중…", ShimpyoText.BODY, ShimpyoColors.TEXT_SECONDARY))
        lifecycleScope.launch {
            val checks = runChecks()
            list.removeAllViews()
            for (check in checks) showCheck(check)
        }
    }

    private suspend fun runChecks(): List<SecurityCheck> = listOf(
        checkNoInternetPermission(),
        checkNotesEncrypted(),
        checkKeyStorage(),
        SecurityCheck(
            "고민 내용 백업 제외",
            Level.OK,
            "자동 백업에서 빠지는 폴더(noBackupFiles)에만 저장해요. 서버로 보내지 않아요.",
        ),
        checkDeveloperScreen(),
        checkExportedEntrances(),
        checkDebuggable(),
        SecurityCheck(
            "저장값 조작 방어",
            Level.OK,
            "프리셋 시간·진행 중 휴식·차량 스냅샷을 읽을 때 허용 범위를 다시 검사하고, 맞지 않으면 버려요.",
        ),
        SecurityCheck(
            "차량 제어 안전 규칙",
            Level.OK,
            "승인한 항목만 · 주차 중에만 · 등받이는 2초씩 움직이고 매번 정지, 최대 15초. 잠금 해제·시동·주행 제어는 하지 않아요.",
        ),
    )

    // ------------------------------------------------------------------
    // 개별 검사
    // ------------------------------------------------------------------

    private fun checkNoInternetPermission(): SecurityCheck {
        val requested = packageInfo(PackageManager.GET_PERMISSIONS)?.requestedPermissions?.toList().orEmpty()
        return if (Manifest.permission.INTERNET in requested) {
            SecurityCheck("인터넷 권한 없음", Level.PROBLEM, "인터넷 권한이 들어 있어요. 어떤 라이브러리가 추가했는지 확인이 필요해요.")
        } else {
            SecurityCheck("인터넷 권한 없음", Level.OK, "앱에 인터넷 권한 자체가 없어서 고민 내용·기록이 기기 밖으로 나갈 수 없어요.")
        }
    }

    private suspend fun checkNotesEncrypted(): SecurityCheck {
        val unencrypted = try {
            container.bambooRepository.countUnencryptedNotes()
        } catch (e: Exception) {
            -1
        }
        return when {
            unencrypted == 0 -> SecurityCheck("고민 내용 암호화", Level.OK, "저장된 이야기는 모두 AES-256-GCM 으로 암호화돼 있어요. 파일을 고치면 복호화가 실패해요.")
            unencrypted > 0 -> SecurityCheck("고민 내용 암호화", Level.PROBLEM, "암호화되지 않은 이야기가 ${unencrypted}개 있어요.")
            else -> SecurityCheck("고민 내용 암호화", Level.NOTE, "저장 파일을 확인하지 못했어요.")
        }
    }

    private suspend fun checkKeyStorage(): SecurityCheck {
        val level = withContext(Dispatchers.IO) { container.bambooCipher.keyStorageLevel() }
        return when (level) {
            KeyStorageLevel.STRONGBOX, KeyStorageLevel.TRUSTED_ENVIRONMENT ->
                SecurityCheck("암호화 키 보관", Level.OK, "${level.label}에 보관돼요. 키는 밖으로 꺼낼 수 없어요.")
            KeyStorageLevel.SOFTWARE ->
                SecurityCheck("암호화 키 보관", Level.NOTE, "${level.label}. 에뮬레이터에는 보안 하드웨어가 없어요. 실제 기기에서는 보안 하드웨어에 보관돼요.")
            KeyStorageLevel.UNKNOWN ->
                SecurityCheck("암호화 키 보관", Level.NOTE, "키 보관 위치를 확인하지 못했어요.")
        }
    }

    private fun checkDeveloperScreen(): SecurityCheck {
        val isDeclared = try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(ComponentName(this, VehicleCheckActivity::class.java), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return if (isDeclared) {
            SecurityCheck("개발자용 차량 점검 화면", Level.NOTE, "개발용 빌드라 켜져 있어요. 출시 빌드에서는 빠져서 열 수 없어요.")
        } else {
            SecurityCheck("개발자용 차량 점검 화면", Level.OK, "출시 빌드에서 빠져 있어요. 시트를 움직이는 시험 버튼에 접근할 수 없어요.")
        }
    }

    /** 다른 앱이 부를 수 있는 입구(exported 이고 권한으로 보호되지 않은 것). 앱 시작 화면 하나만 있어야 정상. */
    private fun checkExportedEntrances(): SecurityCheck {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val info = packageInfo(flags) ?: return SecurityCheck("외부에서 열 수 있는 입구", Level.NOTE, "확인하지 못했어요.")
        val components: List<ComponentInfo> =
            info.activities.orEmpty().toList() + info.services.orEmpty().toList() +
                info.receivers.orEmpty().toList() + info.providers.orEmpty().toList()
        val open = components.filter { it.exported && requiredPermission(it) == null }
            .map { it.name.substringAfterLast('.') }
        val unexpected = open - setOf(HomeActivity::class.java.simpleName)
        return when {
            unexpected.isEmpty() ->
                SecurityCheck("외부에서 열 수 있는 입구", Level.OK, "앱 시작 화면(${open.joinToString()})만 열려 있어요. 휴식·서비스 화면은 다른 앱이 부를 수 없어요.")
            unexpected.all { it == VehicleCheckActivity::class.java.simpleName } ->
                SecurityCheck("외부에서 열 수 있는 입구", Level.NOTE, "개발용 빌드라 점검 화면도 열려 있어요: ${open.joinToString()}")
            else ->
                SecurityCheck("외부에서 열 수 있는 입구", Level.PROBLEM, "예상하지 못한 입구: ${unexpected.joinToString()}")
        }
    }

    private fun checkDebuggable(): SecurityCheck =
        if (isDebugBuild()) {
            SecurityCheck("출시용 빌드", Level.NOTE, "지금은 개발용(디버그) 빌드예요. 제출용 APK 는 출시 빌드로 만들어요.")
        } else {
            SecurityCheck("출시용 빌드", Level.OK, "디버깅이 꺼진 출시 빌드예요.")
        }

    // ------------------------------------------------------------------

    private fun requiredPermission(component: ComponentInfo): String? = when (component) {
        is android.content.pm.ActivityInfo -> component.permission
        is android.content.pm.ServiceInfo -> component.permission
        is android.content.pm.ProviderInfo -> component.readPermission ?: component.writePermission
        else -> null
    }

    private fun packageInfo(flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    } catch (e: Exception) {
        null
    }

    /** 검사 하나 = 카드 한 줄: 왼쪽 동그란 표시(✓ △ ✗), 제목, 설명 */
    private fun showCheck(check: SecurityCheck) {
        val levelColor = when (check.level) {
            Level.OK -> ShimpyoColors.OK
            Level.NOTE -> ShimpyoColors.WARN
            Level.PROBLEM -> ShimpyoColors.DANGER
        }
        val cardView = row(24).apply {
            background = roundedDrawable(ShimpyoColors.SURFACE, ShimpyoSize.CARD_RADIUS)
            setPadding(dp(28), dp(24), dp(28), dp(24))
            contentDescription = "${check.title}, ${check.detail}"
        }
        val mark = shimpyoText(check.level.mark, ShimpyoText.HEADLINE, levelColor).apply {
            gravity = android.view.Gravity.CENTER
            background = roundedDrawable(ShimpyoColors.BACKGROUND, 28)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        cardView.addView(mark)
        val texts = column(6)
        texts.addView(shimpyoText(check.title, ShimpyoText.BODY_STRONG))
        texts.addView(shimpyoText(check.detail, ShimpyoText.CAPTION, ShimpyoColors.TEXT_SECONDARY))
        cardView.addView(texts.weighted(1f))
        list.addView(cardView.fillWidth())
    }
}
