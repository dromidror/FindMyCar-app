# Tasks — Find My Car Navigation

## Task Dependency Graph

```
T1 (DisplacementVector) ──→ T2 (PathAccumulator) ──→ T4 (Service Integration)
                         ──→ T3 (NavigationEngine) ──→ T5 (FindMyCarActivity)
T6 (RoNIN Model Conversion) ──→ T7 (RoninDeadReckoning) ──→ T4
T8 (Arrow UI) ──→ T5
T9 (GPS Anchor Logic) ──→ T4
T10 (ParkingSpotManager Extension) ──→ T4
```

---

## Task 1: DisplacementVector Data Class

- [ ] Create `shared/src/commonMain/kotlin/com/findmycar/shared/DisplacementVector.kt`
- [ ] Define `DisplacementVector` data class with `dNorth: Float` and `dEast: Float` (meters)
- [ ] Add computed property `magnitude` — sqrt(dNorth² + dEast²)
- [ ] Add computed property `bearingDeg` — atan2(dEast, dNorth) in degrees (0=North, 90=East)
- [ ] Add `operator fun plus(other: DisplacementVector)` for accumulation
- [ ] Add `operator fun unaryMinus()` for reversing direction
- [ ] Define `LatLng` data class with `lat: Double` and `lng: Double`
- [ ] Add utility function `LatLng.displaceTo(other: LatLng): DisplacementVector` (GPS distance in meters)
- [ ] Add utility function `LatLng.plus(dv: DisplacementVector): LatLng` (apply displacement to GPS)
- [ ] Add utility function `LatLng.minus(dv: DisplacementVector): LatLng` (reverse displacement from GPS)
- [ ] Add unit tests for all arithmetic and conversion functions

**Acceptance criteria covered:** Req 1 (AC 3), Req 5 (AC 3-4)

---

## Task 2: PathAccumulator

- [ ] Create `shared/src/commonMain/kotlin/com/findmycar/shared/PathAccumulator.kt`
- [ ] Maintain running total `DisplacementVector` (sum of all added vectors)
- [ ] Implement `start()` — sets active flag, resets total
- [ ] Implement `addDisplacement(dv: DisplacementVector)` — adds to running total
- [ ] Implement `stopAndEmit(): DisplacementVector` — returns total, sets inactive
- [ ] Implement `currentTotal(): DisplacementVector` — returns current accumulated value
- [ ] Implement `totalDistance(): Float` — magnitude of current total
- [ ] Implement `isActive: Boolean` property
- [ ] Implement `saveState(prefs: SharedPreferences)` — persists totalNorth, totalEast, isActive
- [ ] Implement `restoreState(prefs: SharedPreferences)` — loads persisted state
- [ ] Implement `reset()` — clears all state
- [ ] GPS gap detection: only active when no GPS fix for 10+ seconds
- [ ] Add unit tests for accumulation, emit, persistence

**Acceptance criteria covered:** Req 3 (all)

---

## Task 3: NavigationEngine

- [ ] Create `shared/src/commonMain/kotlin/com/findmycar/shared/NavigationEngine.kt`
- [ ] Define `NavigationResult` data class: bearingToCarDeg, distanceMeters, scenario (1–4), isGpsBased, arrived
- [ ] Implement `compute(parkingGps, currentGps, accumulatedFromParking, accumulatedFromLastGps): NavigationResult`
- [ ] Scenario 1: both GPS available → compute bearing and distance from GPS coordinates
- [ ] Scenario 2: no parking GPS + current GPS available → resolve parking = currentGps - accumulated → then Scenario 1
- [ ] Scenario 3: parking GPS + no current GPS → estimatedCurrent = lastGps + accumulatedFromLastGps → bearing to parking
- [ ] Scenario 4: no GPS anywhere → reverse accumulated vector (negate) for bearing and magnitude for distance
- [ ] Implement `arrived` flag when distance < 5 meters
- [ ] Implement GPS bearing calculation using haversine formula
- [ ] Implement GPS distance calculation using haversine formula
- [ ] Add unit tests for all 4 scenarios + edge cases (zero distance, null inputs)

**Acceptance criteria covered:** Req 4 (AC 1-5), Req 5 (AC 1, 3-4), Req 6 (all)

---

## Task 4: ExitDetectionService — RoNIN + PathAccumulator Integration

- [ ] Add `PathAccumulator` instance to `ExitDetectionService`
- [ ] On EXITED transition: if GPS available → save GPS (existing). If no GPS → start PathAccumulator
- [ ] Register IMU sensors at high rate (SENSOR_DELAY_FASTEST) when PathAccumulator is active
- [ ] Feed IMU samples to `RoninDeadReckoning` during GPS gaps
- [ ] Every 200ms: run RoNIN inference → add displacement to PathAccumulator
- [ ] Monitor GPS availability: if GPS returns while PathAccumulator active → execute GPS Anchor
- [ ] GPS Anchor: parkingGps = currentGps - pathAccumulator.stopAndEmit() → save to ParkingSpotManager
- [ ] Persist PathAccumulator state on each displacement update (SharedPreferences)
- [ ] Restore PathAccumulator state on service restart
- [ ] Stop RoNIN inference when PathAccumulator stops (GPS returned or Find session ends)

**Acceptance criteria covered:** Req 1 (AC 2-5), Req 2 (AC 2, 4), Req 3 (AC 2-5)

---

## Task 5: FindMyCarActivity (Navigation Screen)

- [ ] Create `app/src/main/kotlin/com/findmycar/app/FindMyCarActivity.kt`
- [ ] Create `app/src/main/res/layout/activity_find_my_car.xml` — dark background, centered arrow, distance text
- [ ] Load parking info from ParkingSpotManager on start
- [ ] Request GPS location updates (FusedLocationProviderClient, 1Hz)
- [ ] Register compass sensor (TYPE_ROTATION_VECTOR) for device heading at SENSOR_DELAY_GAME
- [ ] Start RoNIN inference for dead reckoning when GPS is unavailable
- [ ] Every 100ms (10Hz): compute device heading from rotation vector
- [ ] Every 1000ms (1Hz): call NavigationEngine.compute() with current state
- [ ] Rotate arrow: `arrowRotation = navResult.bearingToCarDeg - deviceHeadingDeg`
- [ ] Update distance text: `"${navResult.distanceMeters.roundToInt()}m"`
- [ ] Show "You're here!" when `navResult.arrived == true`
- [ ] Show accuracy indicator: "GPS ±Xm" or "Low accuracy — estimated"
- [ ] Handle GPS scenario transitions seamlessly (re-compute on GPS gain/loss)
- [ ] Dismiss activity on back press or close button
- [ ] Auto-dismiss if CarPresenceStateMachine → IN_CAR (listen via SharedPreferences change)
- [ ] Register in AndroidManifest.xml

**Acceptance criteria covered:** Req 4 (all), Req 5 (all), Req 7 (all), Req 8 (all)

---

## Task 6: RoNIN Model Conversion

- [ ] Download pre-trained RoNIN model from [Sachini/ronin](https://github.com/Sachini/ronin) (ResNet variant)
- [ ] Write Python conversion script: PyTorch → ONNX → TFLite
- [ ] Verify input shape and preprocessing requirements (normalization, windowing)
- [ ] Verify output format (velocity 2D or displacement 2D)
- [ ] Test inference on sample IMU data to validate outputs make sense
- [ ] Export TFLite model file: `ronin_model.tflite`
- [ ] Upload to Azure models container (alongside car_state_model.tflite)
- [ ] Document input/output format in a `ronin_config.json`

**Acceptance criteria covered:** Req 2 (AC 1, 3)

---

## Task 7: RoninDeadReckoning Wrapper

- [ ] Create `shared/src/commonMain/kotlin/com/findmycar/shared/RoninDeadReckoning.kt`
- [ ] Implement IMU sample buffer (circular buffer, ~200 samples for 1-second window)
- [ ] Implement `addSample(accX, accY, accZ, gyroX, gyroY, gyroZ)` — appends to buffer
- [ ] Implement `infer(interpreter: Interpreter): DisplacementVector?`
  - Returns null if buffer not full enough
  - Preprocesses buffer to model input format (normalize, reshape)
  - Runs TFLite inference
  - Parses output as velocity × dt or direct displacement
  - Returns DisplacementVector
- [ ] Implement `reset()` — clears buffer
- [ ] Implement fallback: if model unavailable, use step_count × 0.7m × compass_heading
- [ ] Add unit tests for buffer management and output parsing

**Acceptance criteria covered:** Req 2 (all)

---

## Task 8: Arrow Drawable and Animation

- [ ] Create `app/src/main/res/drawable/ic_nav_arrow.xml` — large triangular arrow (vector drawable)
- [ ] Arrow should be visually clear at large size (~200dp)
- [ ] Use white or bright color on dark background
- [ ] Implement smooth rotation animation in FindMyCarActivity (interpolate between angles)
- [ ] Handle 360°→0° wrap-around smoothly (shortest path rotation)

**Acceptance criteria covered:** Req 5 (AC 5)

---

## Task 9: GPS Anchor Logic

- [ ] Implement in shared module: `fun gpsAnchorBackward(currentGps: LatLng, displacement: DisplacementVector): LatLng`
  - Converts displacement meters to lat/lng offset
  - Subtracts from current GPS to get parking location
- [ ] Implement: `fun gpsAnchorForward(lastGps: LatLng, displacement: DisplacementVector): LatLng`
  - Adds displacement to last GPS to estimate current position
- [ ] Use standard formulas: 1° lat ≈ 111320m, 1° lng ≈ 111320 × cos(lat) m
- [ ] Add unit tests with known GPS points and displacements

**Acceptance criteria covered:** Req 1 (AC 3-4), Req 6 (AC 2-3)

---

## Task 10: ParkingSpotManager Extension

- [ ] Add `hasRelativeOnly: Boolean` field to ParkingSpot data class
- [ ] Add `relativeNorth: Float?` and `relativeEast: Float?` fields for displacement-only parking
- [ ] Update `save()` to persist relative displacement when GPS unavailable
- [ ] Update `load()` to return relative-only spots
- [ ] Add `resolveWithGps(currentGps: LatLng, displacement: DisplacementVector)` — converts relative to absolute

**Acceptance criteria covered:** Req 1 (AC 4-5)

---

## Task 11: Home Screen "Find" Button + Parked Status

- [ ] Add "Find My Car" button to HomeActivity layout (large, prominent)
- [ ] Button visible only when ParkingSpotManager.load() != null (parking exists)
- [ ] On tap: launch FindMyCarActivity
- [ ] Hide button when state is IN_CAR (no point finding car while driving)
- [ ] Display "🅿️ Car parked" text on Home screen when CarPresenceState == EXITED
- [ ] Show parking time (e.g., "Parked 15 min ago") below the parked text
- [ ] Hide parked text when state is IN_CAR or UNKNOWN

**Acceptance criteria covered:** Req 7 (AC 1)

---

## Implementation Order

1. **T1** — DisplacementVector + LatLng (data classes, pure math)
2. **T9** — GPS Anchor Logic (uses T1)
3. **T2** — PathAccumulator (uses T1)
4. **T3** — NavigationEngine (uses T1, T9)
5. **T10** — ParkingSpotManager Extension
6. **T6** — RoNIN Model Conversion (Python, independent)
7. **T7** — RoninDeadReckoning wrapper (uses T6 output)
8. **T4** — Service Integration (uses T2, T7, T9, T10)
9. **T8** — Arrow drawable + animation
10. **T5** — FindMyCarActivity (uses T3, T8)
11. **T11** — Home Screen button
