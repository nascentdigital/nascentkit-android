# NascentKit Android
An Android framework that simplifies the creation of beautiful apps.


## Installation

1. Include the jcenter() repository in your module's build.grade file:
```groovy

repositories {
    jcenter()
}
```

2. On Android, sure that you've included Java 8 support for simplified Lambda syntax *(requires Android Studio 3+)*:
```groovy

android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}
```

3. Add the NascentKit and RxJava dependencies in your module's build.grade file:
```groovy

dependencies {
    implementation 'io.reactivex.rxjava2:rxjava:2.2.2'
    implementation 'io.reactivex.rxjava2:rxandroid:2.1.0'
    implementation 'com.nascentdigital:nascentkit:0.1.0'
}
```

