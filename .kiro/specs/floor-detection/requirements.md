# Requirements — Parking Floor Detection

## Introduction

Detect which floor the user parked on in a multi-level parking structure using the barometer (pressure sensor). The system calibrates at the entry point (where GPS is lost) and tracks altitude changes as the car descends or ascends ramps. The parking floor is saved alongside the parking location.

## Glossary

- **Entry_Pressure**: The barometer reading recorded when the car enters the parking structure (GPS lost while still driving). This is the calibration point = Floor 0 (entry level).
- **Parking_Pressure**: The barometer reading at the moment the car stops and the user exits.
- **Floor_Delta**: The difference between Parking_Pressure and Entry_Pressure, converted to floors.
- **Floor_Height_hPa**: Pressure change per floor (~0.4 hPa ≈ 3.5–4 meters altitude).
- **GPS_Loss_Event**: The moment GPS becomes unavailable (no fix for 10+ seconds) while the car is still moving.
- **Barometer**: Android's TYPE_PRESSURE sensor, measuring atmospheric pressure in hPa.

## Requirements

### Requirement 1: Barometer Calibration at Parking Entry

**User Story:** As a user, I want the system to automatically calibrate the barometer when I enter an underground parking structure, so that floor calculation has a reliable reference point.

#### Acceptance Criteria

1. WHILE in IN_CAR state AND GPS is available, THE system SHALL continuously record the latest barometer reading as a running baseline.
2. WHEN GPS becomes unavailable (no fix for 10 seconds) AND the car is still moving (CAR_MOVING or CAR_SLOW), THE system SHALL freeze the current barometer baseline as the Entry_Pressure (calibration point = Floor 0).
3. THE Entry_Pressure SHALL be persisted so it survives process restarts.
4. IF GPS returns briefly during underground driving and is lost again, THE system SHALL NOT recalibrate (keep the original Entry_Pressure from the first GPS loss).
5. THE Entry_Pressure SHALL be reset when a new trip starts (state transitions to IN_CAR from EXITED/UNKNOWN).

### Requirement 2: Parking Floor Calculation

**User Story:** As a user, I want to know which floor I parked on relative to the entry level, so I can find the right level when returning to my car.

#### Acceptance Criteria

1. WHEN the CarPresenceStateMachine transitions to EXITED AND an Entry_Pressure was recorded, THE system SHALL compute the parking floor as: `floor = round((parking_pressure - entry_pressure) / FLOOR_HEIGHT_HPA)`.
2. A positive floor value SHALL indicate the car parked below the entry level (deeper underground = higher pressure).
3. A negative floor value SHALL indicate the car parked above the entry level.
4. A floor value of 0 SHALL indicate the car parked on the same level as the entry.
5. THE computed parking floor SHALL be saved alongside the parking spot (latitude, longitude, floor).
6. IF no Entry_Pressure was recorded (GPS never lost, surface parking), THE system SHALL save floor as "ground level" (0).

### Requirement 3: Barometer Monitoring

**User Story:** As a developer, I want the barometer sensor to be monitored efficiently during the drive, so that floor detection works without excessive battery drain.

#### Acceptance Criteria

1. THE system SHALL register for TYPE_PRESSURE sensor events when in IN_CAR state.
2. THE system SHALL sample barometer readings at a rate of 1 Hz (one reading per second).
3. THE system SHALL apply a simple moving average (window = 5 seconds) to smooth out noise from ventilation, door openings, etc.
4. WHEN the state transitions to EXITED or UNKNOWN, THE system SHALL unregister the barometer sensor to conserve battery.
5. THE barometer reading at the time of EXITED transition SHALL be used as the Parking_Pressure.

### Requirement 4: Floor Display

**User Story:** As a user, I want to see which floor my car is parked on, so I know which level to go to when finding my car.

#### Acceptance Criteria

1. WHEN a parking floor has been calculated, THE main screen SHALL display the floor alongside the parking info (e.g., "Parked on B2" or "Parked on Floor -2").
2. THE floor display SHALL use the convention: negative numbers = below entry (B1, B2, B3...), zero = entry level (G), positive = above entry (F1, F2...).
3. IF no floor data is available (surface parking, no barometer), THE system SHALL not display any floor information.
4. THE Find My Car navigation screen SHALL also show the parking floor.

### Requirement 5: Ramp Detection Confirmation (Optional)

**User Story:** As a developer, I want the gyroscope to confirm floor changes by detecting ramp spiral patterns, so that barometer-based floor detection has additional validation.

#### Acceptance Criteria

1. WHILE the car is moving underground (no GPS, CAR_MOVING), THE system SHALL track cumulative yaw rotation from the gyroscope.
2. WHEN cumulative yaw rotation exceeds 300 degrees AND a barometer change of at least 0.3 hPa has occurred since the last ramp detection, THE system SHALL register a ramp_event (one floor transition confirmed).
3. THE ramp_event count SHALL be compared to the barometer-based floor calculation as a sanity check.
4. IF ramp_events and barometer disagree by more than 1 floor, THE system SHALL prefer the barometer-based calculation (barometer is more reliable for total height).
5. The ramp detection is optional — floor detection SHALL work with barometer alone if gyroscope data is unreliable.
