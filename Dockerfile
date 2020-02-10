FROM ubuntu AS BASE

RUN apt-get update
RUN apt-get install -yy curl unzip build-essential zlib1g-dev
WORKDIR "/opt"
RUN curl -sLO https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java8-linux-amd64-19.3.1.tar.gz
RUN tar -xzf graalvm-ce-java8-linux-amd64-19.3.1.tar.gz
ENV GRAALVM_HOME="/opt/graalvm-ce-java8-19.3.1"
ENV JAVA_HOME="/opt/graalvm-ce-java8-19.3.1/bin"
ENV PATH="$PATH:$JAVA_HOME"
COPY . .
RUN apt install -y sudo
RUN ./.circleci/script/install-leiningen
RUN ./script/compile
RUN cp bb /usr/local/bin


FROM ubuntu:bionic
COPY --from=BASE /usr/local/bin/bb /usr/local/bin
CMD ["bb"]
