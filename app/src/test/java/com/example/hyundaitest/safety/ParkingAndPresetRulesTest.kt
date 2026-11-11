package com.example.hyundaitest.safety

import com.example.hyundaitest.data.model.RestPreset
import com.example.hyundaitest.safety.ParkingSourceResult.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 주차 판정 규칙(ParkingReading.fromSources)과 프리셋 범위 규칙 테스트 */
class ParkingAndPresetRulesTest {

    // ------------------------------------------------------------------
    // 주차 판정
    // ------------------------------------------------------------------

    @Test
    fun parking_isParked_whenBrakeOn_andGearUnknown() {
        assertEquals(ParkingState.PARKED, decide(brake = true, gear = null))
    }

    @Test
    fun parking_isParked_whenGearInPark_evenIfBrakeOff() {
        assertEquals(ParkingState.PARKED, decide(brake = false, gear = true))
    }

    @Test
    fun parking_isNotParked_whenGearNotPark_evenIfBrakeOn() {
        // 전자식 파킹브레이크는 D단에서 가속 페달을 밟으면 풀릴 수 있다
        assertEquals(ParkingState.NOT_PARKED, decide(brake = true, gear = false))
    }

    @Test
    fun parking_isNotParked_whenOnlyBrakeReadAndReleased() {
        assertEquals(ParkingState.NOT_PARKED, decide(brake = false, gear = null))
    }

    @Test
    fun parking_isUnknown_whenNothingCouldBeRead() {
        assertEquals(ParkingState.UNKNOWN, decide(brake = null, gear = null))
        assertEquals(ParkingState.UNKNOWN, ParkingReading.fromSources(emptyList()).state)
    }

    @Test
    fun parking_trustsReleased_whenSourcesDisagree() {
        val reading = ParkingReading.fromSources(
            listOf(
                ParkingSourceResult("A", Kind.PARKING_BRAKE, true, ""),
                ParkingSourceResult("B", Kind.PARKING_BRAKE, false, ""),
            )
        )
        assertEquals(false, reading.isParkingBrakeOn)
        assertEquals(ParkingState.NOT_PARKED, reading.state)
    }

    // ------------------------------------------------------------------
    // 프리셋 범위 (짧은 휴식 ≤ 가벼운 낮잠 ≤ 충분한 휴식, 전체 30분 이하)
    // ------------------------------------------------------------------

    @Test
    fun presets_rangesAreOrdered_andWithinSafetyLimits() {
        val rules = SafetyRules()
        val presets = RestPreset.DEFAULTS
        presets.zipWithNext { lower, upper ->
            assertTrue("${lower.name} 최대가 ${upper.name} 최소보다 크면 안 됨", lower.maxMinutes <= upper.minMinutes)
        }
        presets.forEach { preset ->
            assertTrue(preset.minMinutes >= rules.minRestMinutes)
            assertTrue(preset.maxMinutes <= rules.maxRestMinutes)
            assertTrue(preset.minutes in preset.minMinutes..preset.maxMinutes)
        }
    }

    @Test
    fun presets_withMinutes_staysInsideOwnRange() {
        val short = RestPreset.DEFAULTS.first { it.id == RestPreset.ID_SHORT }
        val light = RestPreset.DEFAULTS.first { it.id == RestPreset.ID_LIGHT }
        val full = RestPreset.DEFAULTS.first { it.id == RestPreset.ID_FULL }

        assertEquals(15, short.withMinutes(22).minutes) // 짧은 휴식은 가벼운 낮잠보다 길어지지 않음
        assertEquals(15, light.withMinutes(10).minutes)
        assertEquals(21, light.withMinutes(21).minutes) // 1분 단위
        assertEquals(30, full.withMinutes(45).minutes) // 예전에 저장한 45분도 30분으로
    }

    private fun decide(brake: Boolean?, gear: Boolean?): ParkingState =
        ParkingReading.fromSources(
            listOf(
                ParkingSourceResult("brake", Kind.PARKING_BRAKE, brake, ""),
                ParkingSourceResult("gear", Kind.GEAR_IN_PARK, gear, ""),
            )
        ).state
}
