FROM eclipse-temurin:21-jre AS build
ENV LANG=ja_JP.UTF-8
WORKDIR /minecraft

RUN wget -O paper.jar https://fill-data.papermc.io/v1/objects/e05aaee454117e814e08212efaf0b1e90748fef4c8af741dc1f474928123bb07/paper-1.21.8-19.jar \
    && echo "e05aaee454117e814e08212efaf0b1e90748fef4c8af741dc1f474928123bb07 paper.jar" | sha256sum --check
COPY ./root/eula.txt ./
RUN echo "stop" | java -jar paper.jar
RUN cd /minecraft/plugins \
    && wget https://cdn.modrinth.com/data/UmLGoGij/versions/305Ndn4O/DiscordSRV-Build-1.30.0.jar \
    && wget https://cdn.modrinth.com/data/cUhi3iB2/versions/TQ6Qp5P0/tabtps-spigot-1.3.28.jar \
    && wget https://cdn.modrinth.com/data/p1ewR5kV/versions/Ypqt7eH1/unifiedmetrics-platform-bukkit-0.3.8.jar
RUN echo "stop" | java -jar paper.jar

FROM eclipse-temurin:21-jre AS symlinkbuild
WORKDIR /minecraft
RUN mkdir plugins
COPY ./create-symlinks.sh /tmp/
RUN /tmp/create-symlinks.sh

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

CMD ["paper.jar"]