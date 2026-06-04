# Technical Design — Parking Floor Detection

## Overview

Detects the parking floor using the barometer (TYPE_PRESSURE sensor). Calibrates at the moment GPS is lost while driving (entry level = Floor 0), then measures pressure difference at parking time to determine how many floors below or above entry the car stopped.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│              ExitDetectionService (extended)              │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │            FloorDetector                          │   │
│  │                                                   │   │
│  │  • Monitors TYPE_PRESSURE at 1 Hz                │   │
│  │  • Maintains 5-sample moving average             │   │
│  │  • Calibrates on GPS loss (entry_pressure)       │   │
│  │  • Computes floor at exit time                   │   │
│  └──────────────────────────────────────────────────┘   │
│         │                                                │
│         ▼                                                │
│  ┌──────────────┐                                       │
│  │ ParkingSpot  │ ← saves floor alongside lat/lng       │
│  │ Manager      │                                       │
│  └──────────────┘                                       │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## Component: FloorDetector

**Location:** `shared/src/commonMain/kotlin/com/findmycar/shared/FloorDetector.kt`

Pure logic, no Android dependencies.

```kotlin
class FloorDetector {
    companion object {
        /** Pressure change per floor in hPa (~3.5-4m altitude) */
        const val HPA_PER_FLOOR = 0.4f

        /** Moving average window size (seconds) */
        const val SMOOTHING_WINDOW = 5
    }

    /** Current smoothed pressure reading (hPa) */
    var currentPressure: Float = 0f
        private set

    /** Calibration pressure at entry level (null if not calibrated) */
    var entryPressure: Float? = null
        private set

    /** Whether calibration has been done for this trip */
    val isCalibrated: Boolean get() = entryPressure != null

    /**
     * Feed a pressure reading. Call at ~1 Hz.
     */
    fun addReading(pressureHpa: Float)

    /**
     * Calibrate: freeze current pressure as entry level (Floor 0).
     * Called when GPS is lost while car is moving.
     */
    fun calibrate()

    /**
     * Compute parking floor relative to entry.
     * Negative = below entry (B1, B2...), Positive = above entry (F1, F2...)
     *
     * @return Floor number, or null if not calibrated
     */
    fun computeFloor(): Int?

    /**
     * Get raw pressure delta from entry (for display/debug).
     */
    fun pressureDelta(): Float?

    /**
     * Reset for new trip.
     */
    fun reset()

    /**
     * Persist state.
     */
    fun saveState(): Map<String, Any>
    fun restoreState(state: Map<String, Any>)
}
```

**Internal logic:**

```
addReading(pressure):
  buffer.add(pressure)
  if buffer.size > SMOOTHING_WINDOW: buffer.removeFirst()
  currentPressure = buffer.average()

calibrate():
  entryPressure = currentPressure

computeFloor():
  if entryPressure == null: return null
  delta = currentPressure - entryPressure
  // Higher pressure = lower altitude (underground)
  // Positive delta = went down = negative floor number (B1, B2...)
  return -round(delta / HPA_PER_FLOOR)
```

---

## Integration Points

### 1. ExitDetectionService changes

```kotlin
// Add to service fields:
private val floorDetector = FloorDetector()

// In sensor registration:
sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
}

// In onSensorChanged:
Sensor.TYPE_PRESSURE -> {
    floorDetector.addReading(event.values[0])
}

// On GPS loss while driving (in determineMotionState or GPS callback):
if (!hasRecentGps() && currentMotionState == "CAR_MOVING" && !floorDetector.isCalibrated) {
    floorDetector.calibrate()
}

// On EXITED transition (in onStateTransition):
val parkingFloor = floorDetector.computeFloor()
// Save floor with parking spot

// On new trip (IN_CAR from EXITED):
floorDetector.reset()
```

### 2. ParkingSpot extension

```kotlin
data class ParkingSpot(
    // ... existing fields ...
    val floor: Int? = null,         // null = surface/unknown
    val floorLabel: String? = null  // "B2", "G", "F1"
)
```

### 3. Floor label formatting

```kotlin
fun formatFloor(floor: Int?): String? = when {
    floor == null -> null
    floor == 0 -> "G"       // Ground/entry level
    floor < 0 -> "B${-floor}"  // Basement: B1, B2, B3...
    else -> "F$floor"       // Above: F1, F2...
}
```

---

## Data Flow

```
1. User driving (IN_CAR, GPS available)
   → Barometer reading continuously (smoothed)
   → entryPressure NOT set yet

2. Car enters underground parking (GPS lost, still moving)
   → GPS stale for 10s → calibrate()
   → entryPressure = current smoothed pressure (e.g., 1013.2 hPa)

3. Car descends ramps
   → Pressure increases (going underground)
   → e.g., after 2 floors down: 1014.0 hPa

4. Car parks, user exits (EXITED transition)
   → computeFloor(): (1014.0 - 1013.2) / 0.4 = 2.0 → floor = -2 (B2)
   → Save "Parked on B2" with parking spot

5. New trip starts (EXITED → IN_CAR)
   → floorDetector.reset()
   → Ready for next parking event
```

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| Surface parking (GPS never lost) | No calibration → floor = null → display "Ground" |
| Multi-story above-ground (GPS available on ramp) | GPS may work on open ramps → no calibration → floor = null |
| Car drives between floors flat (no ramp) | Barometer won't change → floor = 0 (same as entry) |
| Weather pressure change during long park | Barometer drift ~1 hPa/hour max → for 1-hour park, error ≈ 2.5 floors. Not a concern for typical parking duration (< 30 min underground). |
| Phone in pocket vs on mount | Doesn't affect barometer (pressure is ambient) |
| Ventilation shafts / doors | Smoothing window handles brief pressure spikes |

---

## Files

### New
| File | Purpose |
|------|---------|
| `shared/.../FloorDetector.kt` | Pressure tracking + floor calculation |

### Modified
| File | Change |
|------|--------|
| `ExitDetectionService.kt` | Add barometer sensor + FloorDetector integration |
| `ParkingSpot.kt` | Add `floor: Int?` field |
| `MainActivity.kt` | Display floor in parked state |
| `FindMyCarActivity.kt` | Show floor on navigation screen |

---

## Sensor Details

| Sensor | Type | Rate | Battery |
|--------|------|------|---------|
| Barometer | TYPE_PRESSURE | 1 Hz (SENSOR_DELAY_NORMAL) | Negligible |

The barometer is one of the lowest-power sensors — always-on is fine.
