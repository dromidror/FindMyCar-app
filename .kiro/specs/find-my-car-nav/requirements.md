# Requirements — Find My Car Navigation

## Introduction

Guide the user back to their parked car using a directional arrow and distance display. The feature works with or without GPS by combining GPS coordinates (when available) with RoNIN pedestrian dead reckoning (when GPS is unavailable). The system automatically determines the parking location when the existing CarPresenceStateMachine transitions to EXITED, and provides real-time navigation when the user taps "Find."

## Glossary

- **Navigation_Screen**: The UI screen displayed when the user taps "Find" — shows a directional arrow and distance to the parked car.
- **RoNIN_Model**: A deep learning model for pedestrian dead reckoning that estimates displacement (distance and direction) from IMU sensor data alone, without GPS.
- **Path_Accumulator**: A component that records displacement vectors produced by the RoNIN_Model during GPS gaps.
- **Displacement_Vector**: A 2D vector (dx, dy) in meters representing movement in north/east coordinates relative to a starting point.
- **GPS_Anchor**: The process of converting accumulated RoNIN displacements to absolute GPS coordinates when a GPS fix becomes available.
- **Parking_Location**: The GPS coordinates or accumulated displacement representing where the car is parked.
- **Find_Session**: The period from when the user taps "Find" until they reach the car or dismiss the Navigation_Screen.
- **ExitDetectionService**: The foreground service that runs the CarPresenceStateMachine and detects car exit events.
- **ParkingSpotManager**: The component that persists parking GPS coordinates.
- **CarPresenceStateMachine**: The state machine that tracks UNKNOWN → IN_CAR → EXITED transitions.

## Requirements

### Requirement 1: Parking Location Determination

**User Story:** As a user, I want my parking location to be automatically determined when I exit my car, so that I can later navigate back to it.

#### Acceptance Criteria

1. WHEN the CarPresenceStateMachine transitions from IN_CAR to EXITED AND GPS is available, THE ParkingSpotManager SHALL save the last known GPS coordinates as the Parking_Location.
2. WHEN the CarPresenceStateMachine transitions from IN_CAR to EXITED AND GPS is not available, THE Path_Accumulator SHALL begin recording RoNIN_Model displacement vectors from the moment of exit.
3. WHEN the Path_Accumulator is active AND a GPS fix becomes available, THE system SHALL compute the Parking_Location as: current_GPS minus accumulated Displacement_Vector (GPS_Anchor backward calculation).
4. WHEN the Parking_Location is computed via GPS_Anchor, THE Path_Accumulator SHALL stop accumulation and persist the resolved GPS coordinates as the Parking_Location.
5. IF GPS never becomes available after exit, THE system SHALL retain the accumulated Displacement_Vector as the Parking_Location representation (relative coordinates from exit point).

### Requirement 2: RoNIN Model Integration

**User Story:** As a developer, I want to integrate the RoNIN pedestrian dead reckoning model, so that the app can estimate walking displacement without GPS.

#### Acceptance Criteria

1. THE RoNIN_Model SHALL accept accelerometer and gyroscope data as input and produce a Displacement_Vector (north/east meters) as output.
2. THE RoNIN_Model SHALL run inference at a fixed interval of 200 milliseconds during active accumulation.
3. THE RoNIN_Model SHALL execute on-device using TFLite without network connectivity.
4. WHEN the RoNIN_Model produces a Displacement_Vector, THE Path_Accumulator SHALL add the vector to the running total displacement.
5. IF the RoNIN_Model fails to load or produces an error, THEN THE system SHALL fall back to step-counter-based distance estimation with the last known heading.

### Requirement 3: Path Accumulation

**User Story:** As a developer, I want a path accumulator that tracks displacement during GPS gaps, so that parking location and navigation can work without GPS.

#### Acceptance Criteria

1. THE Path_Accumulator SHALL maintain a running total Displacement_Vector representing cumulative movement from its start point.
2. THE Path_Accumulator SHALL only run when GPS is unavailable (no valid fix within the last 10 seconds).
3. WHEN GPS becomes available after a gap, THE Path_Accumulator SHALL emit the total accumulated Displacement_Vector and reset.
4. THE Path_Accumulator SHALL persist its state across process restarts so that accumulated displacement is not lost if the service is killed.
5. THE Path_Accumulator SHALL record displacement vectors at RoNIN_Model inference frequency (every 200 milliseconds).

### Requirement 4: Find My Car Navigation Screen

**User Story:** As a user, I want to see a clear arrow and distance pointing to my car when I tap "Find," so I can walk back to it without a map.

#### Acceptance Criteria

1. WHEN the user taps "Find" AND a Parking_Location with GPS coordinates is available AND current GPS is available, THE Navigation_Screen SHALL display an arrow pointing from the user's current GPS position toward the Parking_Location.
2. WHEN the user taps "Find" AND the Parking_Location was determined via GPS AND current GPS is not available, THE Navigation_Screen SHALL display an arrow computed from the RoNIN_Model accumulated displacement forward from the last known GPS position toward the Parking_Location.
3. WHEN the user taps "Find" AND the Parking_Location has no GPS (relative displacement only) AND current GPS is available, THE Navigation_Screen SHALL compute the Parking_Location using GPS_Anchor (current GPS minus accumulated displacement) and display an arrow from current GPS to the resolved Parking_Location.
4. WHEN the user taps "Find" AND no GPS is available at parking or currently, THE Navigation_Screen SHALL display an arrow representing the reverse of the total accumulated RoNIN displacement since exit.
5. THE Navigation_Screen SHALL display the distance to the car in meters, rounded to the nearest meter.
6. THE Navigation_Screen SHALL update the arrow direction and distance in real time as the user walks (minimum update rate: 1 Hz).

### Requirement 5: Arrow Direction Calculation

**User Story:** As a user, I want the arrow to accurately point toward my car regardless of which direction I'm facing.

#### Acceptance Criteria

1. THE Navigation_Screen SHALL compute the bearing from the user's current position to the Parking_Location.
2. THE Navigation_Screen SHALL compensate for the device's current compass heading so that the arrow points toward the car relative to the user's facing direction.
3. WHEN GPS is available, THE system SHALL use GPS coordinates to compute bearing and distance.
4. WHEN GPS is not available, THE system SHALL use the RoNIN_Model accumulated displacement to compute the relative bearing and distance to the parking point.
5. THE arrow rotation SHALL update at a minimum rate of 10 Hz to provide smooth visual feedback during device rotation.

### Requirement 6: GPS Scenario Handling

**User Story:** As a user, I want the navigation to work in all combinations of GPS availability at parking time and finding time.

#### Acceptance Criteria

1. WHEN GPS was available at parking AND GPS is available when finding (Scenario 1), THE system SHALL compute a direct GPS-to-GPS bearing and distance.
2. WHEN GPS was not available at parking AND GPS becomes available later (Scenario 2), THE system SHALL resolve the Parking_Location as: first_GPS_fix minus accumulated_RoNIN_displacement, then navigate GPS-to-GPS.
3. WHEN GPS was available at parking AND GPS is not available when finding (Scenario 3), THE system SHALL navigate by accumulating RoNIN displacement forward from the last known GPS position toward the parking GPS coordinates.
4. WHEN GPS was not available at parking AND GPS is not available when finding (Scenario 4), THE system SHALL navigate using the reverse of the accumulated RoNIN path (displacement from exit point).
5. WHEN the GPS scenario changes during a Find_Session (e.g., GPS becomes available or is lost), THE system SHALL seamlessly transition to the appropriate scenario calculation without user interaction.

### Requirement 7: Find Session Lifecycle

**User Story:** As a user, I want to start and stop navigation easily and have the system manage its resources appropriately.

#### Acceptance Criteria

1. THE Navigation_Screen SHALL be accessible via a "Find" button on the main screen when a Parking_Location exists.
2. WHEN the user taps "Find," THE system SHALL begin requesting location updates and start RoNIN_Model inference for dead reckoning.
3. WHEN the distance to the Parking_Location is less than 5 meters, THE Navigation_Screen SHALL display "You're here!" and stop navigation updates.
4. WHEN the user dismisses the Navigation_Screen, THE system SHALL stop location updates and RoNIN_Model inference to conserve battery.
5. IF the CarPresenceStateMachine transitions to IN_CAR during a Find_Session, THEN THE system SHALL automatically end the Find_Session and dismiss the Navigation_Screen.

### Requirement 8: Navigation Accuracy Feedback

**User Story:** As a user, I want to understand how confident the navigation is, so I know whether to trust the arrow or look around.

#### Acceptance Criteria

1. WHEN navigating with GPS (Scenarios 1 or 2 resolved), THE Navigation_Screen SHALL display the GPS accuracy radius in meters.
2. WHEN navigating with RoNIN dead reckoning only (Scenarios 3 or 4), THE Navigation_Screen SHALL display a "Low accuracy — estimated" indicator.
3. WHEN the computed distance to the car is less than the GPS accuracy radius, THE Navigation_Screen SHALL display "Car is nearby" instead of a directional arrow.
4. THE system SHALL not display a numeric accuracy value for RoNIN-based navigation (only qualitative indicator).
