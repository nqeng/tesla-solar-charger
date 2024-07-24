# tesla-solar-charger

FIXME: description

## Installation

- Requires [Java 17](https://www.oracle.com/au/java/technologies/downloads/#java17)
- Download the latest version from [releases](https://github.com/nqeng/tesla-solar-charger/releases)
- Create a `.env` file in the directory that the program is to be run from
- run `java -jar solar-tesla-charger-1.0.0-standalone.jar`

## Usage

    $ java -jar solar-tesla-charger-1.0.0-standalone.jar

## Installation from source

- Requires [Java 17](https://www.oracle.com/au/java/technologies/downloads/#java17)
- Requires [Clojure](https://clojure.org/guides/install_clojure)
- Requires [Leiningen](https://leiningen.org/)
- Clone this repo
- Create a `.env` file in the project root directory
- use leiningen to build and run: `lein run`

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
# Exact location of the charger; Tesla will only charge when near this location
CHARGER_LATITUDE=
CHARGER_LONGITUDE=
# System-specific Sungrow API information
GRID_SENSOR_DEVICE_ID=
GRID_POWER_DATA_ID=
```