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

See "NOTE: GoSungrow Issues" below.

### How to find the codes for the GoSungrowDataSource

The GoSungrowDataSource requires a few values on init. These are the PS_KEY, PS_ID, and excess power key, or PS_POINT.

These values are specific to your Sungrow system and can be found using a helpful tool called [GoSungrow](https://github.com/MickMake/GoSungrow).

(You may have already downloaded it to run the data source).

First, follow any setup instructions in the GoSungrow README file.

Done? Great.

We basically want to find which PS_POINT corresponds to the excess power in watts for your system.

You can use `GoSungrow show ps points` to list all data points and their ids:

```bash
$ GoSungrow show ps points

# Available points:
┏━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━┳━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Id       ┃ Name                                                                       ┃ Unit   ┃ Unit Type ┃ Ps Id   ┃ Device Type ┃ Device Name                  ┃
┣━━━━━━━━━━╇━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━╇━━━━━━━━━━━╇━━━━━━━━━╇━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫

...

┃ p8014    │ PF                                                                         │        │ 0         │ 1152381 │ 7           │ DTSD1352(COM1-254)_003_002   ┃
┃ p8018    │ Meter Active Power                                                         │ kW     │ 3         │ 1152381 │ 7           │ DTSD1352(COM1-254)_003_002   ┃
┃ p8022    │ Reactive Power                                                             │ kvar   │ 8         │ 1152381 │ 7           │ DTSD1352(COM1-254)_003_002   ┃
┃ p8026    │ Apparent Power                                                             │ VA     │ 39        │ 1152381 │ 7           │ DTSD1352(COM1-254)_003_002   ┃
...

```

In my case, the one I want is *Meter Active Power*, so my PS_POINT is p8018.

Your mileage may vary, but the GoSungrowDataSource basically needs to know which id to use to get the excess power from the Sungrow API.

On top of the PS_POINT p8018, we also need the PS ID and Device Type, which are conveniently listed.

So my PS_ID = 1152381

And my Device Type = 7

Now we need to find the PS_KEY. To do this, use:

```bash
$ GoSungrow show ps list

┏━━━━━━━━━━━━━━━━━━┳━━━━━━━━━┳━━━━━━━━━━━━━┳━━━━━━━━━━━━━┳━━━━━━━━━━━━┳━━━━━━━━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━┓
┃ Ps Key           ┃ Ps Id   ┃ Device Type ┃ Device Code ┃ Channel Id ┃ Serial #    ┃ Factory Name ┃ Device Model   ┃
┣━━━━━━━━━━━━━━━━━━╇━━━━━━━━━╇━━━━━━━━━━━━━╇━━━━━━━━━━━━━╇━━━━━━━━━━━━╇━━━━━━━━━━━━━╇━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━━┫
┃ 1256712_1_1_1    │ 1256712 │ 1           │ 1           │ 1          │ B2321768386 │ SUNGROW      │ SG5.0RS        ┃
┃ 1256712_7_1_1    │ 1256712 │ 7           │ 1           │ 1          │ B2321768386 │ SUNGROW      │ SG Smart Meter ┃
┃ 1256712_22_247_1 │ 1256712 │ 22          │ 247         │ 1          │ B2321768386 │ SUNGROW      │ WiNet-S        ┃
┃ 1152381_1_1_3    │ 1152381 │ 1           │ 1           │ 3          │ B2192412984 │ SUNGROW      │ SG30CX         ┃
┃ 1152381_7_2_3    │ 1152381 │ 7           │ 2           │ 3          │ B2192412984 │              │                ┃
┃ 1152381_22_247_3 │ 1152381 │ 22          │ 247         │ 3          │ B2192412984 │ SUNGROW      │ EyeM4          ┃
┗━━━━━━━━━━━━━━━━━━┷━━━━━━━━━┷━━━━━━━━━━━━━┷━━━━━━━━━━━━━┷━━━━━━━━━━━━┷━━━━━━━━━━━━━┷━━━━━━━━━━━━━━┷━━━━━━━━━━━━━━━━┛
```

With PS ID = 1152381 and Device Type = 7, our PS_KEY is 1152381_7_2_3.

With these three values found:

PS_ID = 1152381

PS_KEY = 1152381_7_2_3

PS_POINT = p8018

We can pass them to our GoSungrowDataSource on init.

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
