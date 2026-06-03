# Technical Design — Find My Car Navigation

## Overview

Guides the user back to their parked car with a directional arrow and distance. Combines GPS (when available) with RoNIN pedestrian dead reckoning (when GPS is unavailable). Integrates with the ExitDetectionService and CarPresenceStateMachine.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ExitDetectionService                          │
│                        (Foreground Service)                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────┐   ┌──────────────┐   ┌───────────────────────┐   │
│  │ Motion Model │   │ GPS Tracker  │   │ RoNIN Dead Reckoning  │   │
│  │ (car state)  │   │ (last fix)   │   │ (displacement vectors)│   │
│  └──────┬───────┘   └──────┬───────┘   └──────────┬────────────┘   │
│         │                   │                       │                │
│         ▼                   ▼                       ▼                │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    PathAccumulator                             │   │
│  │  • Runs during GPS gaps                                       │   │
│  │  • Total displacement (dx_north, dy_east) in meters           │   │
│  │  • Emits + resets when GPS returns                            │   │
│  └──────────────────────────────────────────────────────────────┘   │
│         │                                                            │
│         ▼                                                            │
│  ┌──────────────┐   ┌──────────────────┐                           │
│  │ ParkingSpot  │   │ GPS Anchor Logic │                           │
│  │ Manager      │←──│ (backward calc)  │                           │
│  └──────────────┘   └──────────────────┘                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                      FindMyCarActivity                                │
│                     (Navigation Screen)                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    NavigationEngine                            │   │
│  │  • Determines current GPS scenario (1–4)                      │   │
│  │  • Computes bearing + distance to parking spot                │   │
│  │  • Compensates for device compass heading                     │   │
│  └──────────────────────────────────────────────────────────────┘   │
│         │                                                            │
│         ▼                                                            │
│  ┌──────────────────────┐   ┌────────────────────────────────┐     │
│  │   Arrow View          │   │   Distance + Accuracy Text     │     │
│  │   (rotates at 10Hz)   │   │   "47m" / "You're here!"      │     │
│  └───────────────────────┘   └────────────────────────────────┘     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## New Components

### 1. RoninDeadReckoning

**Location:** `shared/src/commonMain/kotlin/com/findmycar/shared/RoninDeadReckoning.kt`

Pure logic wrapper — handles windowing and output parsing for the RoNIN TFLite model.

```kotlin
class RoninDeadReckoning {
    companion object {
        const val INFERENCE_INTERVAL_MS = 200L
        const val INPUT_WINDOW_SIZE = 200  // samples at ~200Hz → 1 second window
    }

    /** Feed raw IMU sample. Call at sensor rate (~200Hz). */
    fun addSample(accX: Float, accY: Float, accZ: Float,
                  gyroX: Float, gyroY: Float, gyroZ: Float)

    /**
     * Run inference when enough samples accumulated.
     * Returns displacement (dx_north, dy_east) in meters since last inference,
     * or null if not enough data yet.
     */
    fun infer(interpreter: Interpreter): DisplacementVector?

    fun reset()
}

data class DisplacementVector(
    val dNorth: Float,  // meters, positive = north
    val dEast: Float    // meters, positive = east
) {
    val magnitude: Float get() = sqrt(dNorth * dNorth + dEast * dEast)
    val bearingDeg: Float get() = Math.toDegrees(atan2(dEast.toDouble(), dNorth.toDouble())).toFloat()
}
```

### 2. PathAccumulator

**Location:** `shared/src/commonMain/kotlin/com/findmycar/shared/PathAccumulator.kt`

Accumulates displacement vectors during GPS gaps.

```kotlin
class PathAccumulator {
    var totalNorth: Float = 0f
        private set
    var totalEast: Float = 0f
        private set
    var isActive: Boolean = false
        private set

    fun start()
    fun addDisplacement(dv: DisplacementVector)
    fun stopAndEmit(): DisplacementVector
    fun currentTotal(): DisplacementVector
    fun totalDistance(): Float
    fun saveTo(prefs: Map<String, Any>)
    fun restoreFrom(prefs: Map<String, Any>)
    fun reset()
}
```

### 3. NavigationEngine

**Location:** `shared/src/commonMain/kotlin/com/findmycar/shared/NavigationEngine.kt`

Computes arrow bearing and distance given current scenario.

```kotlin
data class NavigationResult(
    val bearingToCarDeg: Float,      // absolute bearing (0=north, 90=east)
    val distanceMeters: Float,
    val scenario: Int,               // 1–4
    val isGpsBased: Boolean,
    val arrived: Boolean             // distance < 5m
)

class NavigationEngine {
    fun compute(
        parkingGps: LatLng?,
        currentGps: LatLng?,
        accumulatedFromParking: DisplacementVector?,
        accumulatedFromLastGps: DisplacementVector?
    ): NavigationResult
}

data class LatLng(val lat: Double, val lng: Double)
```

**Scenario logic:**

```
Scenario 1: parkingGps != null AND currentGps != null
  → GPS-to-GPS bearing and distance

Scenario 2: parkingGps == null AND currentGps != null AND accumulatedFromParking != null
  → parkingGps = currentGps - accumulatedFromParking → then Scenario 1

Scenario 3: parkingGps != null AND currentGps == null AND accumulatedFromLastGps != null
  → estimatedCurrent = lastGps + accumulatedFromLastGps → bearing to parkingGps

Scenario 4: parkingGps == null AND currentGps == null AND accumulatedFromParking != null
  → vector to car = negative of accumulatedFromParking
```

### 4. FindMyCarActivity

**Location:** `app/src/main/kotlin/com/findmycar/app/FindMyCarActivity.kt`

Navigation screen with arrow + distance.

### 5. Layout: `activity_find_my_car.xml`

```
FrameLayout (full screen, dark background)
├── Large arrow ImageView (centered, rotated)
├── Distance TextView (below arrow, large bold: "47m")
├── Accuracy indicator (small text)
├── Status text ("You're here!")
└── Close button (top-right)
```

---

## RoNIN Model Details

**Input:** Window of IMU data (accelerometer + gyroscope, ~200 samples at 200Hz = 1 second)
**Output:** 2D velocity vector (north m/s, east m/s)

**Model source:** [Sachini/ronin](https://github.com/Sachini/ronin)

**Integration:**
1. Convert pre-trained RoNIN model to TFLite
2. Ship as asset or download from Azure
3. Input: normalized IMU window
4. Output: velocity × time_interval = displacement

**Fallback:** step_count × 0.7m with compass heading.

---

## Coordinate System

**NED frame (North-East-Down):**
- `dNorth` = positive northward (meters)
- `dEast` = positive eastward (meters)

**GPS ↔ meters:**
- 1° latitude ≈ 111,320 m
- 1° longitude ≈ 111,320 × cos(lat) m

**Bearing:** 0° = North, 90° = East, 180° = South, 270° = West

---

## Battery Impact

| State | RoNIN | GPS | Impact |
|-------|-------|-----|--------|
| EXITED, GPS available | Off | 1Hz | Low |
| EXITED, no GPS | On (200ms) | Checking 1/10s | Medium |
| Find Session, GPS | Off | 1Hz + 10Hz compass | Low-Medium |
| Find Session, no GPS | On (200ms) | Checking + 10Hz compass | Medium |

---

## Persistence

| Data | Storage | Survives |
|------|---------|----------|
| Parking GPS | SharedPreferences | App kill + reboot |
| Parking relative displacement | SharedPreferences | App kill + reboot |
| PathAccumulator state | SharedPreferences | Service kill |
| RoNIN model file | App files dir | Permanent |
| Find session state | Activity memory | Activity only |
