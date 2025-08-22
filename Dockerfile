FROM eclipse-temurin:21 AS plugin-build
WORKDIR /plugin
COPY ./mcplugin/*.* ./mcplugin/gradlew ./
COPY ./mcplugin/gradle ./gradle
COPY ./mcplugin/snaptaker/build.gradle ./snaptaker/build.gradle
COPY ./mcplugin/preserveinventory/build.gradle ./preserveinventory/settings.gradle
RUN ./gradlew paperweightUserdevSetup
COPY ./mcplugin/snaptaker/src ./snaptaker/src
COPY ./mcplugin/preserveinventory/src ./preserveinventory/src
RUN ./gradlew jar

FROM eclipse-temurin:21 AS build
ENV LANG=ja_JP.UTF-8
WORKDIR /minecraft

RUN wget -O paper.jar https://fill-data.papermc.io/v1/objects/e05aaee454117e814e08212efaf0b1e90748fef4c8af741dc1f474928123bb07/paper-1.21.8-19.jar \
    && echo "e05aaee454117e814e08212efaf0b1e90748fef4c8af741dc1f474928123bb07 paper.jar" | sha256sum --check
COPY ./root/eula.txt ./
RUN echo "stop" | java -jar paper.jar
RUN cd /minecraft/plugins \
    && wget https://cdn.modrinth.com/data/UmLGoGij/versions/305Ndn4O/DiscordSRV-Build-1.30.0.jar \
    && wget https://cdn.modrinth.com/data/cUhi3iB2/versions/TQ6Qp5P0/tabtps-spigot-1.3.28.jar \
    && wget https://cdn.modrinth.com/data/p1ewR5kV/versions/Ypqt7eH1/unifiedmetrics-platform-bukkit-0.3.8.jar \
    && wget https://cdn.modrinth.com/data/swbUV1cr/versions/bhZhBtEw/bluemap-5.11-paper.jar \
    && wget https://cdn.modrinth.com/data/MswVHkMy/versions/LL1WyFsf/SignMarkers-0.0.1.jar \
    && wget https://hangarcdn.papermc.io/plugins/harry/PortableCrafting/versions/2.0.0/PAPER/PortableCrafting-2.0.0.jar
RUN echo "stop" | java -jar paper.jar

COPY --from=plugin-build /plugin/snaptaker/build/libs/*.jar /minecraft/plugins/
COPY --from=plugin-build /plugin/preserveinventory/build/libs/*.jar /minecraft/plugins/
RUN echo "stop" | java -jar paper.jar

FROM eclipse-temurin:21 AS symlinkbuild
WORKDIR /minecraft
RUN mkdir plugins
COPY ./create-symlinks.sh /tmp/
RUN /tmp/create-symlinks.sh

FROM golang:1.24.5-alpine3.22 as go-build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

FROM go-build as go-prestop
COPY ./prestop ./prestop
RUN go build -o /prestop ./prestop/

FROM go-build as go-snapshotter
COPY ./snapshotter ./snapshotter
RUN go build -o /snapshotter ./snapshotter/

FROM go-build as go-snapuploader
COPY ./snapuploader ./snapuploader
RUN go build -o /snapuploader ./snapuploader/

FROM alpine:3.22 AS snapshotter
RUN apk add --no-cache btrfs-progs
COPY --from=go-snapshotter /snapshotter /snapshotter

FROM alpine:3.22 AS snapuploader
RUN apk add --no-cache btrfs-progs zstd
COPY --from=go-snapuploader /snapuploader /snapuploader

FROM gcr.io/distroless/java21-debian12:debug-nonroot
ENV LANG=ja_JP.UTF-8

COPY --from=symlinkbuild /minecraft /minecraft
WORKDIR /minecraft

COPY ./root/* ./
COPY --from=build /minecraft/paper.jar ./
COPY --from=build /minecraft/libraries ./libraries
COPY --from=build /minecraft/cache ./cache
COPY --from=build /minecraft/versions ./versions
COPY --from=build /minecraft/plugins/*.jar ./plugins/
COPY --from=build /minecraft/plugins/.paper-remapped ./plugins/.paper-remapped
COPY --from=go-prestop /prestop /

CMD ["paper.jar"]