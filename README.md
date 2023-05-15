# sungrow-tesla

FIXME: description

## Installation

Download from http://example.com/FIXME.

## Usage

FIXME: explanation

    $ java -jar sungrow-tesla-0.1.0-standalone.jar [args]

### Environment
```bash
# Auth
SUNGROW_USERNAME=
SUNGROW_PASSWORD=
TESSIE_TOKEN=
TESLA_VIN=
# Tuning
# Always maintains at least this amount of power going to the grid
POWER_BUFFER_WATTS=
# Max amount charge speed can climb at any one time
MAX_CLIMB_AMPS=
# Max amount charge speed can drop at any one time
MAX_DROP_AMPS=
# Latitude/Longitude boundaries of a square geographic 
# "fence" around the charger. Includes north, south,
# east, and west boundaries.
CHARGER_GEOFENCE_NORTH=
CHARGER_GEOFENCE_SOUTH=
CHARGER_GEOFENCE_WEST=
CHARGER_GEOFENCE_EAST=
# Sungrow inverter-specific IDs
GRID_SENSOR_DEVICE_ID=
GRID_POWER_DATA_ID=
```

## Options

FIXME: listing of options this app accepts.

## Examples

...

### Bugs

...

### Any Other Sections
### That You Think
### Might be Useful

## License

Copyright © 2023 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
