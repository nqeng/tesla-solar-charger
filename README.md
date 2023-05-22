# sungrow-tesla

FIXME: description

## Installation

- Requires [Java 17](https://www.oracle.com/au/java/technologies/downloads/#java17)
- Download the latest version from [releases](https://github.com/nqeng/tesla-solar-charger/releases)
- Create a `.env` file in the directory that the program is to be run from

## Installation from source

- Requires [Java 17](https://www.oracle.com/au/java/technologies/downloads/#java17)
- Requires [Clojure](https://clojure.org/guides/install_clojure)
- Requires [Leiningen](https://leiningen.org/)
- Clone this repo
- Create a `.env` file
- run `lein run` in the repo root folder

## Usage

FIXME: explanation

    $ java -jar sungrow-tesla-1.0.0-standalone.jar

## Options

The program can be customized using environment variables stored in a `.env` file in the project root folder.

Below is a template for a `.env` file that contains all variables that can be changed:

```bash
# Sungrow
SUNGROW_USERNAME=
SUNGROW_PASSWORD=
# Tessie
TESSIE_TOKEN=
TESLA_VIN=
# Always maintain at least this amount of power going to the grid
POWER_BUFFER_WATTS=
# Maximum amount the Tesla charge speed can change at any one time
# (Integer only)
MAX_CLIMB_AMPS=
MAX_DROP_AMPS=
# Square geological boundary around the charger; Tesla will only
# charge while it is within this boundary
# Latitudinal boundaries
CHARGER_GEOFENCE_NORTH=
CHARGER_GEOFENCE_SOUTH=
# Longitudinal boundaries
CHARGER_GEOFENCE_WEST=
CHARGER_GEOFENCE_EAST=
# System-specific Sungrow API information
GRID_SENSOR_DEVICE_ID=
GRID_POWER_DATA_ID=
```

## Modification

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
