FROM node:18-alpine AS build-web
ADD . /app
WORKDIR /app/web
RUN yarn config set ignore-engines true && yarn --ignore-engines && yarn build

FROM gradle:6.1.1-jdk8 AS build-env
ADD --chown=gradle:gradle . /app
WORKDIR /app
COPY --from=build-web /app/web/dist /app/src/main/resources/web
RUN rm src/main/java/com/htmake/reader/ReaderUIApplication.kt; \
    gradle -b cli.gradle assemble --info; \
    mv ./build/libs/*.jar ./build/libs/reader.jar

FROM amazoncorretto:8u332-alpine3.14-jre
RUN apk add --no-cache ca-certificates tini tzdata; \
    update-ca-certificates; \
    rm -rf /var/cache/apk/*
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["/sbin/tini", "--"]
COPY --from=build-env /app/build/libs/reader.jar /app/bin/reader.jar
CMD ["java", "-jar", "/app/bin/reader.jar"]
