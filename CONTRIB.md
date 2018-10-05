# Contributing
Just some notes for the developers.

## Environment Setup
To develop the project, setup your development environment as follows:

1. Download and install the latest version of [Android Studio](https://developer.android.com/studio/index.html).
2. Clone the repository and get started.

## Deployment
To deploy a new version of the library:

1. Run all unit tests in the `nascentkit/src/test/java` folder.
2. Edit the `local.properties` file on the project root and add the following keys:
```shell
bintray.user=YOUR_USERNAME
bintray.apikey=YOUR_APIKEY
```
3. Execute the following command from the project root folder:

```shell
> ./gradlew install
> ./gradlew bintrayUpload
```