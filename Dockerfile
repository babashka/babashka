FROM clojure:lein-2.9.1 AS BASE
ARG BABASHKA_XMX="-J-Xmx3g"

RUN apt update
RUN apt install --no-install-recommends -yy curl unzip build-essential zlib1g-dev
WORKDIR "/opt"
RUN curl -sLO https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java8-linux-amd64-19.3.1.tar.gz
RUN tar -xzf graalvm-ce-java8-linux-amd64-19.3.1.tar.gz

ENV GRAALVM_HOME="/opt/graalvm-ce-java8-19.3.1"
ENV JAVA_HOME="/opt/graalvm-ce-java8-19.3.1/bin"
ENV PATH="$PATH:$JAVA_HOME"
ENV BABASHKA_STATIC="true"
ENV BABASHKA_XMX=$BABASHKA_XMX

COPY . .
RUN ./script/uberjar
RUN ./script/compile

FROM alpine:latest

RUN apk add --no-cache curl
RUN mkdir -p /usr/local/bin
COPY --from=BASE /opt/bb /usr/local/bin/bb
CMD ["bb"]
