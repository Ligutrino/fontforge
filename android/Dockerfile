# Imagen para compilar FF Glifos: motor de FontForge (NDK) y la app Android (.apk).
FROM ubuntu:24.04

ARG NDK_VERSION=r27c
ARG CMDLINE_TOOLS=11076708
ARG GRADLE_VERSION=8.11.1
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip git make cmake ninja-build pkg-config python3 file \
        openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

# Android NDK (compilador nativo)
RUN curl -fsSL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip \
    && unzip -q /tmp/ndk.zip -d /opt \
    && rm /tmp/ndk.zip
ENV ANDROID_NDK=/opt/android-ndk-${NDK_VERSION}

# Android SDK: plataforma 35 y herramientas de compilación
ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
    && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/tools.zip \
    && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
    && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;34.0.0" "build-tools;35.0.0" "platform-tools" >/dev/null \
    && chmod -R a+rX $ANDROID_HOME

# Gradle
RUN curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
    && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
ENV PATH=/opt/gradle-${GRADLE_VERSION}/bin:$PATH

WORKDIR /work
COPY build.sh build-app.sh prueba.pe /work/scripts/
COPY app /work/app
RUN chmod +x /work/scripts/*.sh && chmod -R a+rX /work
ENTRYPOINT ["/work/scripts/build-app.sh"]
