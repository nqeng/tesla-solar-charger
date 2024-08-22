# tesla-solar-charger

Clojure program to regulate the charge speed of a Tesla to draw the excess power from a solar system.

## The Basics

This project has been developed for [North Queensland Engineering](https://www.nqeng.com.au/).

You are welcome to modify the code for yourself.

This project is built with [Leiningen](https://leiningen.org/).

## For anybody wanting to adapt this software to your needs

This codebase is made up of components that can be combined to suit your needs.

An example configuration being used at NQE can be found in [`core.clj`](https://github.com/nqeng/tesla-solar-charger/blob/main/src/tesla_solar_charger/core.clj).

I recommend forking this repository and replacing it with your own configuration.

Currently, this program is designed to work with a Tesla managed by the [Tessie](https://tessie.com/) API and a [Sungrow](https://au.isolarcloud.com/) solar system.

Using the interfaces provided, you may create any implementation of a car data source, solar data source, or regulator.

### NOTE ON THE GOSUNGROW DATA SOURCE

If you are using the [GoSungrowDataSource](https://github.com/nqeng/tesla-solar-charger/blob/main/src/tesla_solar_charger/solar_data_source/gosungrow_data_source.clj), take note:

This data source is intended to interact with a binary of [GoSungrow](https://github.com/MickMake/GoSungrow) using subprocesses.

You need to download a release of GoSungrow and provide the filepath to the binary when you init the GoSungrowDataSource.

See "GoSungrow Issues" below.

### How to find the codes for the GoSungrowDataSource

The GoSungrowDataSource requires a few values on init. These are the PS_KEY, PS_ID, and excess power key, or PS_POINT.

These values are specific to your Sungrow system and can be found using a helpful tool called [GoSungrow](https://github.com/MickMake/GoSungrow).

(You may have already downloaded it to run the data source).



## For anybody wanting to maintain the program for NQE

The current state of this repository is what is being used at NQE.

The company has a docker image hosted on Docker Hub at [nqeng/tesla-solar-charger](https://hub.docker.com/r/nqeng/tesla-solar-charger).

This image is likely running on some computer in the NQE office right now.

To make a change, change the code and after testing with `lein run`, build a new uberjar with `lein uberjar`.

To update the docker image after changing the code, you may use the included [Dockerfile](https://github.com/nqeng/tesla-solar-charger/blob/main/Dockerfile) to build a new image.

The current setup of the project is using the [GoSungrowDataSource](https://github.com/nqeng/tesla-solar-charger/blob/main/src/tesla_solar_charger/solar_data_source/gosungrow_data_source.clj).

This means that the Dockerfile will require a binary of [GoSungrow](https://github.com/MickMake/GoSungrow) to be present in the project root directory before the docker image can be built.

Simply grab a binary of this project [here](https://github.com/MickMake/GoSungrow/releases/latest) and place it into the project root folder (where the Dockerfile is).

You should then be able to build the docker image, test it, and then push it to nqeng/tesla-solar-charger (with the correct permissions) to run in production.

Running the docker image requires a few environment variables to be set. A quick look at [`core.clj`](https://github.com/nqeng/tesla-solar-charger/blob/main/src/tesla_solar_charger/core.clj) should reveal which environment variables are required.

## NOTE: GoSungrow Issues

GoSungrow has been having issues lately. I have been having to refer to [this gist](https://gist.github.com/Paraphraser/cad3b0aa6428c58ee87bc835ac12ed37) to produce a working GoSungrow binary.
