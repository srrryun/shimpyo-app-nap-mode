package com.example.hyundaitest

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import com.example.hyundaitest.data.BambooRepository
import com.example.hyundaitest.data.NapSessionRepository
import com.example.hyundaitest.data.PresetRepository
import com.example.hyundaitest.data.RestHistoryRepository
import com.example.hyundaitest.data.local.FileBambooRepository
import com.example.hyundaitest.data.local.FileRestHistoryRepository
import com.example.hyundaitest.data.local.PrefsNapSessionRepository
import com.example.hyundaitest.data.local.AppSettings
import com.example.hyundaitest.data.local.PrefsPresetRepository
import com.example.hyundaitest.nap.AndroidAlarmSound
import com.example.hyundaitest.nap.LogcatNapEventLogger
import com.example.hyundaitest.nap.NapController
import com.example.hyundaitest.nap.RestDraft
import com.example.hyundaitest.nap.ServiceRestKeepAlive
import com.example.hyundaitest.nap.TimeSource
import com.example.hyundaitest.safety.BasicSafetyGuard
import com.example.hyundaitest.safety.SafetyGuard
import com.example.hyundaitest.safety.SafetyRules
import com.example.hyundaitest.security.KeystoreTextCipher
import com.example.hyundaitest.vehicle.NoVehicleMonitor
import com.example.hyundaitest.vehicle.NoVehicleRestEnvironment
import com.example.hyundaitest.vehicle.PleosRestEnvironment
import com.example.hyundaitest.vehicle.PleosVehicleMonitor
import com.example.hyundaitest.vehicle.SimulatableVehicleMonitor
import com.example.hyundaitest.vehicle.VehicleMonitor
import com.example.hyundaitest.vehicle.VehicleSignalReader
import com.example.hyundaitest.vehicle.ParkingStatusReader
import com.example.hyundaitest.vehicle.RestEnvironment
import com.example.hyundaitest.vehicle.VehicleParkingStatusReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 앱 전체에서 쓰는 부품을 만드는 "한 곳" (설계서 11.1 확장 설계).
 *
 * 구현을 바꾸고 싶으면 이 파일만 고친다.
 *  - Room 전환:      bambooRepository = RoomBambooRepository(...)
 *  - 2단계 차량 제어: restEnvironment = PleosRestEnvironment(...)
 *  - 차량 없이 시험:  parkingStatusReader = NoVehicleParkingStatusReader()
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** 앱이 살아 있는 동안 유지되는 작업 공간 (화면이 꺼져도 휴식 타이머는 계속) */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val safetyRules = SafetyRules()

    /** 휴식 시작 전 화면 사이에서 고른 값 (메모리에만, 3단계 UI) */
    val restDraft = RestDraft()

    /** 앱 설정 (설정 화면) */
    val appSettings = AppSettings(appContext)
    val safetyGuard: SafetyGuard = BasicSafetyGuard(safetyRules)

    // ----- 저장소 (1차: SharedPreferences·파일) -----
    val presetRepository: PresetRepository = PrefsPresetRepository(appContext)
    /** 대나무숲 암호화 (키스토어 AES-256-GCM, 보안 A) */
    val bambooCipher = KeystoreTextCipher()
    val bambooRepository: BambooRepository = FileBambooRepository(appContext, bambooCipher)
    val restHistoryRepository: RestHistoryRepository = FileRestHistoryRepository(appContext)
    val napSessionRepository: NapSessionRepository = PrefsNapSessionRepository(appContext)

    // ----- 차량 -----
    /**
     * 앱 전체가 함께 쓰는 Vehicle SDK 연결. 처음 필요할 때 한 번만 연결한다.
     * Pleos 차량(에뮬레이터)이 아니어서 연결에 실패하면 null — 이때 차량 기능은 "확인 불가"로 동작한다.
     */
    val vehicle: Vehicle? by lazy {
        try {
            Vehicle(appContext).also { it.initialize() }
        } catch (e: Exception) {
            Log.w("ShimpyoVehicle", "Vehicle SDK 연결 실패: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 주차 상태 자동 확인 (읽기만 함) */
    val parkingStatusReader: ParkingStatusReader = VehicleParkingStatusReader(appContext, vehicleProvider = { vehicle })

    /** 쉼표 시그널: 배터리·문 잠금·공조 상태 읽기 */
    val vehicleSignalReader = VehicleSignalReader(vehicleProvider = { vehicle })

    /** 휴식 환경 제어. 차량에 연결되지 않으면 아무것도 제어하지 않는 구현을 쓴다. */
    val restEnvironment: RestEnvironment = vehicle?.let {
        PleosRestEnvironment(
            context = appContext,
            vehicle = it,
            scope = appScope,
            rules = safetyRules,
            isStillParked = PleosRestEnvironment.parkedCheckFrom(parkingStatusReader),
        )
    } ?: NoVehicleRestEnvironment()

    /**
     * 휴식 중 차량 감시 (문·브레이크·주차·배터리).
     * 개발용 흉내 버튼을 위해 SimulatableVehicleMonitor 로 감싼다. 실제 감시는 그대로 동작한다.
     */
    val vehicleMonitor = SimulatableVehicleMonitor(
        real = vehicle?.let {
            PleosVehicleMonitor(vehicle = it, scope = appScope, parkingStatusReader = parkingStatusReader, rules = safetyRules)
        } ?: NoVehicleMonitor(),
    )

    val napController = NapController(
        scope = appScope,
        timeSource = TimeSource.SYSTEM,
        rules = safetyRules,
        safetyGuard = safetyGuard,
        alarmSound = AndroidAlarmSound(appContext),
        keepAlive = ServiceRestKeepAlive(appContext),
        restEnvironment = restEnvironment,
        sessionRepository = napSessionRepository,
        historyRepository = restHistoryRepository,
        bambooRepository = bambooRepository,
        vehicleMonitor = vehicleMonitor,
        logger = LogcatNapEventLogger(),
    )
}
